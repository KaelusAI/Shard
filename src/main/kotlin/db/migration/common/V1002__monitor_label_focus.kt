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
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("ClassName")
class V1002__monitor_label_focus : BaseJavaMigration() {
  override fun migrate(context: Context) {
    val connection = context.connection
    if (!tableExists(connection, MONITOR_SETTINGS_TABLE)) return
    if (columnExists(connection, MONITOR_SETTINGS_TABLE, LABEL_FOCUS_COLUMN)) return
    executeSql(
      connection,
      "ALTER TABLE $MONITOR_SETTINGS_TABLE " +
        "ADD COLUMN $LABEL_FOCUS_COLUMN VARCHAR(64) NOT NULL DEFAULT ''",
    )
  }

  private fun tableExists(connection: Connection, table: String): Boolean {
    val metadata = connection.metaData
    buildTableLookups(connection).forEach { (catalog, schema) ->
      metadata.getTables(catalog, schema, table, arrayOf("TABLE")).use { resultSet ->
        if (resultSet.next()) return true
      }
    }
    return false
  }

  private fun columnExists(connection: Connection, table: String, column: String): Boolean {
    val metadata = connection.metaData
    buildTableLookups(connection).forEach { (catalog, schema) ->
      metadata.getColumns(catalog, schema, table, column).use { resultSet ->
        if (resultSet.next()) return true
      }
    }
    return false
  }

  private fun executeSql(connection: Connection, sql: String) {
    connection.createStatement().use { statement -> statement.execute(sql) }
  }

  private fun buildTableLookups(connection: Connection): List<Pair<String?, String?>> =
    listOf(
      connection.catalog to connection.schema,
      connection.catalog to null,
      null to connection.schema,
      null to null,
    )

  private companion object {
    private const val MONITOR_SETTINGS_TABLE = "monitor_settings"
    private const val LABEL_FOCUS_COLUMN = "label_focus"
  }
}
