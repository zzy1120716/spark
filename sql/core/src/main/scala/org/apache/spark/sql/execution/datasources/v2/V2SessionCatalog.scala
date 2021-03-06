/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.v2

import java.net.URI
import java.util

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalog.v2.{Identifier, NamespaceChange, SupportsNamespaces, TableCatalog, TableChange}
import org.apache.spark.sql.catalog.v2.NamespaceChange.{RemoveProperty, SetProperty}
import org.apache.spark.sql.catalog.v2.expressions.{BucketTransform, FieldReference, IdentityTransform, Transform}
import org.apache.spark.sql.catalog.v2.utils.CatalogV2Util
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{NamespaceAlreadyExistsException, NoSuchNamespaceException, NoSuchTableException, TableAlreadyExistsException}
import org.apache.spark.sql.catalyst.catalog.{BucketSpec, CatalogDatabase, CatalogTable, CatalogTableType, CatalogUtils, SessionCatalog}
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.internal.SessionState
import org.apache.spark.sql.sources.v2.Table
import org.apache.spark.sql.sources.v2.internal.UnresolvedTable
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

/**
 * A [[TableCatalog]] that translates calls to the v1 SessionCatalog.
 */
class V2SessionCatalog(sessionState: SessionState) extends TableCatalog with SupportsNamespaces {
  import org.apache.spark.sql.catalog.v2.CatalogV2Implicits._
  import V2SessionCatalog._

  def this() = {
    this(SparkSession.active.sessionState)
  }

  override val defaultNamespace: Array[String] = Array("default")

  private lazy val catalog: SessionCatalog = sessionState.catalog

  private var _name: String = _

  override def name: String = _name

  override def initialize(name: String, options: CaseInsensitiveStringMap): Unit = {
    this._name = name
  }

  override def listTables(namespace: Array[String]): Array[Identifier] = {
    namespace match {
      case Array(db) =>
        catalog.listTables(db).map(ident => Identifier.of(Array(db), ident.table)).toArray
      case _ =>
        throw new NoSuchNamespaceException(namespace)
    }
  }

  override def loadTable(ident: Identifier): Table = {
    val catalogTable = try {
      catalog.getTableMetadata(ident.asTableIdentifier)
    } catch {
      case _: NoSuchTableException =>
        throw new NoSuchTableException(ident)
    }

    UnresolvedTable(catalogTable)
  }

  override def invalidateTable(ident: Identifier): Unit = {
    catalog.refreshTable(ident.asTableIdentifier)
  }

  override def createTable(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): Table = {

    val (partitionColumns, maybeBucketSpec) = V2SessionCatalog.convertTransforms(partitions)
    val provider = properties.getOrDefault("provider", sessionState.conf.defaultDataSourceName)
    val tableProperties = properties.asScala
    val location = Option(properties.get(LOCATION_TABLE_PROP))
    val storage = DataSource.buildStorageFormatFromOptions(tableProperties.toMap)
        .copy(locationUri = location.map(CatalogUtils.stringToURI))
    val tableType = if (location.isDefined) CatalogTableType.EXTERNAL else CatalogTableType.MANAGED

    val tableDesc = CatalogTable(
      identifier = ident.asTableIdentifier,
      tableType = tableType,
      storage = storage,
      schema = schema,
      provider = Some(provider),
      partitionColumnNames = partitionColumns,
      bucketSpec = maybeBucketSpec,
      properties = tableProperties.toMap,
      tracksPartitionsInCatalog = sessionState.conf.manageFilesourcePartitions,
      comment = Option(properties.get(COMMENT_TABLE_PROP)))

    try {
      catalog.createTable(tableDesc, ignoreIfExists = false)
    } catch {
      case _: TableAlreadyExistsException =>
        throw new TableAlreadyExistsException(ident)
    }

    loadTable(ident)
  }

  override def alterTable(
      ident: Identifier,
      changes: TableChange*): Table = {
    val catalogTable = try {
      catalog.getTableMetadata(ident.asTableIdentifier)
    } catch {
      case _: NoSuchTableException =>
        throw new NoSuchTableException(ident)
    }

    val properties = CatalogV2Util.applyPropertiesChanges(catalogTable.properties, changes)
    val schema = CatalogV2Util.applySchemaChanges(catalogTable.schema, changes)

    try {
      catalog.alterTable(catalogTable.copy(properties = properties, schema = schema))
    } catch {
      case _: NoSuchTableException =>
        throw new NoSuchTableException(ident)
    }

    loadTable(ident)
  }

  override def dropTable(ident: Identifier): Boolean = {
    try {
      if (loadTable(ident) != null) {
        catalog.dropTable(
          ident.asTableIdentifier,
          ignoreIfNotExists = true,
          purge = true /* skip HDFS trash */)
        true
      } else {
        false
      }
    } catch {
      case _: NoSuchTableException =>
        false
    }
  }

  override def renameTable(oldIdent: Identifier, newIdent: Identifier): Unit = {
    if (tableExists(newIdent)) {
      throw new TableAlreadyExistsException(newIdent)
    }

    // Load table to make sure the table exists
    loadTable(oldIdent)
    catalog.renameTable(oldIdent.asTableIdentifier, newIdent.asTableIdentifier)
  }

  implicit class TableIdentifierHelper(ident: Identifier) {
    def asTableIdentifier: TableIdentifier = {
      ident.namespace match {
        case Array(db) =>
          TableIdentifier(ident.name, Some(db))
        case Array() =>
          TableIdentifier(ident.name, Some(catalog.getCurrentDatabase))
        case _ =>
          throw new NoSuchTableException(ident)
      }
    }
  }

  override def namespaceExists(namespace: Array[String]): Boolean = namespace match {
    case Array(db) =>
      catalog.databaseExists(db)
    case _ =>
      false
  }

  override def listNamespaces(): Array[Array[String]] = {
    catalog.listDatabases().map(Array(_)).toArray
  }

  override def listNamespaces(namespace: Array[String]): Array[Array[String]] = {
    namespace match {
      case Array() =>
        listNamespaces()
      case Array(db) if catalog.databaseExists(db) =>
        Array()
      case _ =>
        throw new NoSuchNamespaceException(namespace)
    }
  }

  override def loadNamespaceMetadata(namespace: Array[String]): util.Map[String, String] = {
    namespace match {
      case Array(db) =>
        catalog.getDatabaseMetadata(db).toMetadata

      case _ =>
        throw new NoSuchNamespaceException(namespace)
    }
  }

  override def createNamespace(
      namespace: Array[String],
      metadata: util.Map[String, String]): Unit = namespace match {
    case Array(db) if !catalog.databaseExists(db) =>
      catalog.createDatabase(
        toCatalogDatabase(db, metadata, defaultLocation = Some(catalog.getDefaultDBPath(db))),
        ignoreIfExists = false)

    case Array(_) =>
      throw new NamespaceAlreadyExistsException(namespace)

    case _ =>
      throw new IllegalArgumentException(s"Invalid namespace name: ${namespace.quoted}")
  }

  override def alterNamespace(namespace: Array[String], changes: NamespaceChange*): Unit = {
    namespace match {
      case Array(db) =>
        // validate that this catalog's reserved properties are not removed
        changes.foreach {
          case remove: RemoveProperty if RESERVED_PROPERTIES.contains(remove.property) =>
            throw new UnsupportedOperationException(
              s"Cannot remove reserved property: ${remove.property}")
          case _ =>
        }

        val metadata = catalog.getDatabaseMetadata(db).toMetadata
        catalog.alterDatabase(
          toCatalogDatabase(db, CatalogV2Util.applyNamespaceChanges(metadata, changes)))

      case _ =>
        throw new NoSuchNamespaceException(namespace)
    }
  }

  override def dropNamespace(namespace: Array[String]): Boolean = namespace match {
    case Array(db) if catalog.databaseExists(db) =>
      if (catalog.listTables(db).nonEmpty) {
        throw new IllegalStateException(s"Namespace ${namespace.quoted} is not empty")
      }
      catalog.dropDatabase(db, ignoreIfNotExists = false, cascade = false)
      true

    case Array(_) =>
      // exists returned false
      false

    case _ =>
      throw new NoSuchNamespaceException(namespace)
  }

  override def toString: String = s"V2SessionCatalog($name)"
}

private[sql] object V2SessionCatalog {
  val COMMENT_TABLE_PROP: String = "comment"
  val LOCATION_TABLE_PROP: String = "location"
  val RESERVED_PROPERTIES: Set[String] = Set(COMMENT_TABLE_PROP, LOCATION_TABLE_PROP)

  /**
   * Convert v2 Transforms to v1 partition columns and an optional bucket spec.
   */
  private def convertTransforms(partitions: Seq[Transform]): (Seq[String], Option[BucketSpec]) = {
    val identityCols = new mutable.ArrayBuffer[String]
    var bucketSpec = Option.empty[BucketSpec]

    partitions.map {
      case IdentityTransform(FieldReference(Seq(col))) =>
        identityCols += col

      case BucketTransform(numBuckets, FieldReference(Seq(col))) =>
        bucketSpec = Some(BucketSpec(numBuckets, col :: Nil, Nil))

      case transform =>
        throw new UnsupportedOperationException(
          s"SessionCatalog does not support partition transform: $transform")
    }

    (identityCols, bucketSpec)
  }

  private def toCatalogDatabase(
      db: String,
      metadata: util.Map[String, String],
      defaultLocation: Option[URI] = None): CatalogDatabase = {
    CatalogDatabase(
      name = db,
      description = metadata.getOrDefault(COMMENT_TABLE_PROP, ""),
      locationUri = Option(metadata.get(LOCATION_TABLE_PROP))
          .map(CatalogUtils.stringToURI)
          .orElse(defaultLocation)
          .getOrElse(throw new IllegalArgumentException("Missing database location")),
      properties = metadata.asScala.toMap -- Seq("comment", "location"))
  }

  private implicit class CatalogDatabaseHelper(catalogDatabase: CatalogDatabase) {
    def toMetadata: util.Map[String, String] = {
      val metadata = mutable.HashMap[String, String]()

      catalogDatabase.properties.foreach {
        case (key, value) => metadata.put(key, value)
      }
      metadata.put(LOCATION_TABLE_PROP, catalogDatabase.locationUri.toString)
      metadata.put(COMMENT_TABLE_PROP, catalogDatabase.description)

      metadata.asJava
    }
  }
}
