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
package ac.shard.database

import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.logging.Logger

private const val NO_ROWS_UPDATED = 0
private const val PARAM_NEW_VERSION = 1
private const val PARAM_OLD_VERSION = 2
private const val PARAM_DESCRIPTION = 3

private val MOVED_MIGRATIONS =
  mapOf(
    "9" to ("monitor collect inference" to "1000"),
    "10" to ("multilabel storage" to "1001"),
    "11" to ("monitor collect inference" to "1000"),
    "12" to ("multilabel storage" to "1001"),
  )

internal fun renumberMovedMigrations(dataSource: HikariDataSource, logger: Logger): Boolean {
  dataSource.connection.use { connection ->
    if (!flywaySchemaHistoryTableExists(connection)) return false
    return MOVED_MIGRATIONS.filter { (oldVersion, move) ->
        renumberOne(connection, oldVersion, move.first, move.second, logger)
      }
      .isNotEmpty()
  }
}

private fun renumberOne(
  connection: Connection,
  oldVersion: String,
  description: String,
  newVersion: String,
  logger: Logger,
): Boolean {
  connection
    .prepareStatement(
      "UPDATE flyway_schema_history SET version = ? WHERE version = ? AND description = ?"
    )
    .use { statement ->
      statement.setString(PARAM_NEW_VERSION, newVersion)
      statement.setString(PARAM_OLD_VERSION, oldVersion)
      statement.setString(PARAM_DESCRIPTION, description)
      if (statement.executeUpdate() == NO_ROWS_UPDATED) return false
    }
  logger.warning(
    "[DB] Migration '$description' moved from V$oldVersion to V$newVersion; " +
      "rewrote the Flyway history so the database keeps its data"
  )
  return true
}
