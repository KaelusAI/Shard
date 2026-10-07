/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * Shard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Shard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package db.migration.common

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.util.Locale
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("ClassName")
class V2__align_schema : BaseJavaMigration() {
  override fun migrate(context: Context) {
    val connection = context.connection
    val dialect = SqlDialect.fromProductName(connection.metaData.databaseProductName)

    ensureViolationsCreatedAtInstantColumn(connection, dialect)
    ensureMonitorShowNameColumn(connection, dialect)
    ensureViolationsIndexes(connection, dialect)
    backfillCreatedAtInstant(connection, dialect)
  }

  private fun ensureViolationsCreatedAtInstantColumn(connection: Connection, dialect: SqlDialect) {
    if (!tableExists(connection, VIOLATIONS_TABLE)) {
      return
    }
    if (columnExists(connection, VIOLATIONS_TABLE, CREATED_AT_INSTANT_COLUMN)) {
      return
    }

    val definition =
      when (dialect) {
        SqlDialect.SQLITE -> "TEXT NOT NULL DEFAULT '$SQLITE_EPOCH_INSTANT'"
        SqlDialect.MYSQL -> "DATETIME(3) NOT NULL DEFAULT '$SQLITE_EPOCH_INSTANT'"
        SqlDialect.OTHER -> "TIMESTAMP NOT NULL DEFAULT '$SQLITE_EPOCH_INSTANT'"
      }
    executeSql(
      connection,
      "ALTER TABLE $VIOLATIONS_TABLE ADD COLUMN $CREATED_AT_INSTANT_COLUMN $definition",
    )
  }

  private fun ensureMonitorShowNameColumn(connection: Connection, dialect: SqlDialect) {
    if (!tableExists(connection, MONITOR_SETTINGS_TABLE)) {
      return
    }
    if (columnExists(connection, MONITOR_SETTINGS_TABLE, SHOW_NAME_COLUMN)) {
      return
    }

    val definition =
      when (dialect) {
        SqlDialect.SQLITE -> "TEXT NOT NULL DEFAULT '$DEFAULT_SHOW_NAME'"
        else -> "VARCHAR(16) NOT NULL DEFAULT '$DEFAULT_SHOW_NAME'"
      }
    executeSql(
      connection,
      "ALTER TABLE $MONITOR_SETTINGS_TABLE ADD COLUMN $SHOW_NAME_COLUMN $definition",
    )
  }

  private fun ensureViolationsIndexes(connection: Connection, dialect: SqlDialect) {
    if (!tableExists(connection, VIOLATIONS_TABLE)) {
      return
    }

    val existingIndexes = existingIndexColumns(connection, VIOLATIONS_TABLE)
    VIOLATIONS_INDEXES.forEach { (indexName, columns) ->
      val normalizedColumns = columns.map { it.lowercase(Locale.ROOT) }
      if (!existingIndexes.contains(normalizedColumns)) {
        executeSql(connection, createIndexSql(dialect, indexName, VIOLATIONS_TABLE, columns))
      }
    }
  }

  private fun backfillCreatedAtInstant(connection: Connection, dialect: SqlDialect) {
    if (!tableExists(connection, VIOLATIONS_TABLE)) {
      return
    }
    if (
      !columnExists(connection, VIOLATIONS_TABLE, CREATED_AT_COLUMN) ||
        !columnExists(connection, VIOLATIONS_TABLE, CREATED_AT_INSTANT_COLUMN)
    ) {
      return
    }

    val updateSql =
      when (dialect) {
        SqlDialect.SQLITE ->
          """
          UPDATE $VIOLATIONS_TABLE
          SET $CREATED_AT_INSTANT_COLUMN = strftime('%Y-%m-%d %H:%M:%f', $CREATED_AT_COLUMN / 1000.0, 'unixepoch')
          WHERE $CREATED_AT_INSTANT_COLUMN = '$SQLITE_EPOCH_INSTANT'
          """
            .trimIndent()
        SqlDialect.MYSQL ->
          """
          UPDATE $VIOLATIONS_TABLE
          SET $CREATED_AT_INSTANT_COLUMN = FROM_UNIXTIME($CREATED_AT_COLUMN / 1000.0)
          WHERE $CREATED_AT_INSTANT_COLUMN = '$SQLITE_EPOCH_INSTANT'
          """
            .trimIndent()
        SqlDialect.OTHER -> null
      }

    if (updateSql != null) {
      executeSql(connection, updateSql)
    }
  }

  private fun createIndexSql(
    dialect: SqlDialect,
    indexName: String,
    tableName: String,
    columns: List<String>,
  ): String {
    val joinedColumns = columns.joinToString(", ")
    return when (dialect) {
      SqlDialect.SQLITE -> "CREATE INDEX IF NOT EXISTS $indexName ON $tableName ($joinedColumns)"
      else -> "CREATE INDEX $indexName ON $tableName ($joinedColumns)"
    }
  }

  private fun tableExists(connection: Connection, tableName: String): Boolean =
    MigrationSchema.tableExists(connection, tableName)

  private fun columnExists(connection: Connection, tableName: String, columnName: String): Boolean =
    MigrationSchema.columnExists(connection, tableName, columnName)

  private fun existingIndexColumns(connection: Connection, tableName: String): Set<List<String>> =
    readIndexColumns(connection.metaData, connection.catalog, connection.schema, tableName)

  private fun readIndexColumns(
    metadata: DatabaseMetaData,
    catalog: String?,
    schema: String?,
    tableName: String,
  ): Set<List<String>> {
    val columnsByIndex = mutableMapOf<String, MutableList<Pair<Int, String>>>()
    metadata.getIndexInfo(catalog, schema, tableName, false, false).use { resultSet ->
      while (resultSet.next()) {
        val indexName = resultSet.getString("INDEX_NAME")
        val columnName = resultSet.getString("COLUMN_NAME")
        val position = resultSet.getInt("ORDINAL_POSITION")
        val hasUsableIndexName = !indexName.isNullOrBlank() && !indexName.equals("PRIMARY", true)
        val hasUsableColumn = !columnName.isNullOrBlank() && position > 0
        if (hasUsableIndexName && hasUsableColumn) {
          val normalizedIndexName = indexName.lowercase(Locale.ROOT)
          val normalizedColumn = columnName.lowercase(Locale.ROOT)
          columnsByIndex
            .getOrPut(normalizedIndexName) { mutableListOf() }
            .add(position to normalizedColumn)
        }
      }
    }
    return columnsByIndex.values
      .map { indexedColumns -> indexedColumns.sortedBy { it.first }.map { it.second } }
      .toSet()
  }

  private enum class SqlDialect {
    SQLITE,
    MYSQL,
    OTHER;

    companion object {
      fun fromProductName(productName: String?): SqlDialect {
        val normalized = productName?.lowercase(Locale.ROOT).orEmpty()
        return when {
          normalized.contains("sqlite") -> SQLITE
          normalized.contains("mysql") || normalized.contains("mariadb") -> MYSQL
          else -> OTHER
        }
      }
    }
  }

  private companion object {
    private const val VIOLATIONS_TABLE = "violations"
    private const val MONITOR_SETTINGS_TABLE = "monitor_settings"
    private const val CREATED_AT_COLUMN = "created_at"
    private const val CREATED_AT_INSTANT_COLUMN = "created_at_instant"
    private const val SHOW_NAME_COLUMN = "show_name"
    private const val SQLITE_EPOCH_INSTANT = "1970-01-01 00:00:00.000"
    private const val DEFAULT_SHOW_NAME = "AUTO"

    private val VIOLATIONS_INDEXES =
      listOf(
        "violations_uuid_created_at_idx" to listOf("uuid", "created_at"),
        "violations_created_at_idx" to listOf("created_at"),
        "violations_uuid_created_at_instant_idx" to listOf("uuid", "created_at_instant"),
        "violations_created_at_instant_idx" to listOf("created_at_instant"),
      )
  }
}

private fun executeSql(connection: Connection, sql: String) {
  connection.createStatement().use { statement -> statement.execute(sql) }
}
