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

// Rows written before this migration keep an empty labels string. They are not backfilled: a
// binary model never decided which label a flag belonged to.
@Suppress("ClassName")
class V12__multilabel_storage : BaseJavaMigration() {
  override fun migrate(context: Context) {
    val connection = context.connection
    addViolationLabels(connection)
    createLabelBuffers(connection)
  }

  private fun addViolationLabels(connection: Connection) {
    if (!tableExists(connection, VIOLATIONS_TABLE)) return
    if (columnExists(connection, VIOLATIONS_TABLE, LABELS_COLUMN)) return
    executeSql(
      connection,
      "ALTER TABLE $VIOLATIONS_TABLE ADD COLUMN $LABELS_COLUMN VARCHAR(255) NOT NULL DEFAULT ''",
    )
  }

  private fun createLabelBuffers(connection: Connection) {
    if (tableExists(connection, LABEL_BUFFERS_TABLE)) return
    executeSql(
      connection,
      """
      CREATE TABLE $LABEL_BUFFERS_TABLE (
        uuid VARCHAR(36) NOT NULL,
        label VARCHAR(64) NOT NULL,
        buffer DOUBLE PRECISION NOT NULL DEFAULT 0,
        updated_at BIGINT NOT NULL DEFAULT 0,
        PRIMARY KEY (uuid, label)
      )
      """
        .trimIndent(),
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
    private const val VIOLATIONS_TABLE = "violations"
    private const val LABELS_COLUMN = "labels"
    private const val LABEL_BUFFERS_TABLE = "ai_label_buffers"
  }
}
