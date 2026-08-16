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
import java.util.Locale
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("ClassName")
class V11__monitor_collect_inference : BaseJavaMigration() {
  override fun migrate(context: Context) {
    val connection = context.connection
    val dialect = SqlDialect.fromProductName(connection.metaData.databaseProductName)
    ensureColumn(connection, dialect, SHOW_COLLECT_COLUMN)
    ensureColumn(connection, dialect, SHOW_INFERENCE_COLUMN)
  }

  private fun ensureColumn(connection: Connection, dialect: SqlDialect, column: String) {
    if (!tableExists(connection, MONITOR_SETTINGS_TABLE)) {
      return
    }
    if (columnExists(connection, MONITOR_SETTINGS_TABLE, column)) {
      return
    }

    val definition =
      when (dialect) {
        SqlDialect.SQLITE -> "INTEGER NOT NULL DEFAULT 1"
        SqlDialect.MYSQL -> "BOOLEAN NOT NULL DEFAULT TRUE"
        SqlDialect.OTHER -> "BOOLEAN NOT NULL DEFAULT TRUE"
      }
    executeSql(connection, "ALTER TABLE $MONITOR_SETTINGS_TABLE ADD COLUMN $column $definition")
  }

  private fun tableExists(connection: Connection, tableName: String): Boolean {
    val metadata = connection.metaData
    buildTableLookups(connection).forEach { (catalog, schema) ->
      metadata.getTables(catalog, schema, tableName, arrayOf("TABLE")).use { resultSet ->
        if (resultSet.next()) {
          return true
        }
      }
    }
    return false
  }

  private fun columnExists(connection: Connection, tableName: String, columnName: String): Boolean {
    val metadata = connection.metaData
    buildTableLookups(connection).forEach { (catalog, schema) ->
      metadata.getColumns(catalog, schema, tableName, columnName).use { resultSet ->
        if (resultSet.next()) {
          return true
        }
      }
    }
    return false
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
    private const val MONITOR_SETTINGS_TABLE = "monitor_settings"
    private const val SHOW_COLLECT_COLUMN = "show_collect"
    private const val SHOW_INFERENCE_COLUMN = "show_inference"
  }
}

private fun executeSql(connection: Connection, sql: String) {
  connection.createStatement().use { statement -> statement.execute(sql) }
}

private fun buildTableLookups(connection: Connection): List<Pair<String?, String?>> {
  return listOf(
    connection.catalog to connection.schema,
    connection.catalog to null,
    null to connection.schema,
    null to null,
  )
}
