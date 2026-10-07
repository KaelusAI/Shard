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
import java.util.UUID
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("ClassName")
class V1005__punish_flags : BaseJavaMigration() {
  override fun migrate(context: Context) {
    val connection = context.connection
    if (tableExists(connection, FLAGS)) return
    execute(
      connection,
      "CREATE TABLE $FLAGS (id VARCHAR(36) NOT NULL PRIMARY KEY, uuid VARCHAR(36) NOT NULL, " +
        "punish_group VARCHAR(255) NOT NULL, flagged_at BIGINT NOT NULL)",
    )
    execute(connection, "CREATE INDEX idx_${FLAGS}_player ON $FLAGS (uuid, punish_group)")
    if (tableExists(connection, LEGACY)) copyCounts(connection)
  }

  private fun copyCounts(connection: Connection) {
    val now = System.currentTimeMillis()
    val rows = mutableListOf<Triple<String, String, Int>>()
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT uuid, punish_group, vl FROM $LEGACY").use { result ->
        while (result.next()) {
          rows +=
            Triple(result.getString("uuid"), result.getString("punish_group"), result.getInt("vl"))
        }
      }
    }
    connection
      .prepareStatement(
        "INSERT INTO $FLAGS (id, uuid, punish_group, flagged_at) VALUES (?, ?, ?, ?)"
      )
      .use { insert ->
        for ((uuid, group, count) in rows) {
          repeat(count.coerceIn(0, MAX_COPIED)) {
            insert.setString(ID_PARAM, UUID.randomUUID().toString())
            insert.setString(UUID_PARAM, uuid)
            insert.setString(GROUP_PARAM, group)
            insert.setLong(AT_PARAM, now)
            insert.addBatch()
          }
        }
        insert.executeBatch()
      }
  }

  private fun tableExists(connection: Connection, table: String): Boolean =
    MigrationSchema.tableExists(connection, table)

  private fun execute(connection: Connection, sql: String) {
    connection.createStatement().use { it.execute(sql) }
  }

  private companion object {
    const val FLAGS = "shard_punish_flags"
    const val LEGACY = "shard_punishments"
    const val MAX_COPIED = 1000
    const val ID_PARAM = 1
    const val UUID_PARAM = 2
    const val GROUP_PARAM = 3
    const val AT_PARAM = 4
  }
}
