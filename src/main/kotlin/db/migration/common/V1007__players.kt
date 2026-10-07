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
class V1007__players : BaseJavaMigration() {
  override fun migrate(context: Context) {
    val connection = context.connection
    if (tableExists(connection, PLAYERS)) return
    execute(
      connection,
      "CREATE TABLE $PLAYERS (uuid VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL, " +
        "name_lower VARCHAR(64) NOT NULL, last_seen BIGINT NOT NULL)",
    )
    execute(connection, "CREATE INDEX idx_${PLAYERS}_name ON $PLAYERS (name_lower, last_seen)")
    if (tableExists(connection, VIOLATIONS)) copyNames(connection)
  }

  private fun latestNames(connection: Connection): Map<String, Pair<String, Long>> {
    val latest = HashMap<String, Pair<String, Long>>()
    val sql =
      "SELECT uuid, player_name, MAX(created_at) AS seen FROM $VIOLATIONS GROUP BY uuid, player_name"
    val rows = mutableListOf<Triple<String, String, Long>>()
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use { result ->
        while (result.next()) {
          rows +=
            Triple(
              result.getString("uuid"),
              result.getString("player_name").orEmpty(),
              result.getLong("seen"),
            )
        }
      }
    }
    for ((uuid, name, seen) in rows) {
      if (name.isNotBlank() && (latest[uuid]?.second ?: Long.MIN_VALUE) < seen) {
        latest[uuid] = name to seen
      }
    }
    return latest
  }

  private fun copyNames(connection: Connection) {
    val latest = latestNames(connection)
    connection
      .prepareStatement(
        "INSERT INTO $PLAYERS (uuid, name, name_lower, last_seen) VALUES (?, ?, ?, ?)"
      )
      .use { insert ->
        for ((uuid, entry) in latest) {
          insert.setString(UUID_PARAM, uuid)
          insert.setString(NAME_PARAM, entry.first)
          insert.setString(LOWER_PARAM, entry.first.lowercase(Locale.ROOT))
          insert.setLong(SEEN_PARAM, entry.second)
          insert.addBatch()
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
    const val PLAYERS = "shard_players"
    const val VIOLATIONS = "violations"
    const val UUID_PARAM = 1
    const val NAME_PARAM = 2
    const val LOWER_PARAM = 3
    const val SEEN_PARAM = 4
  }
}
