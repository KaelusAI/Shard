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

internal object MigrationSchema {
  fun tableExists(connection: Connection, table: String): Boolean =
    if (isMySql(connection)) {
      informationSchema(
        connection,
        "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
        table,
      )
    } else {
      connection.metaData
        .getTables(connection.catalog, connection.schema, table, arrayOf("TABLE"))
        .use { it.next() }
    }

  fun columnExists(connection: Connection, table: String, column: String): Boolean =
    if (isMySql(connection)) {
      informationSchema(
        connection,
        "SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
          "AND TABLE_NAME = ? AND COLUMN_NAME = ?",
        table,
        column,
      )
    } else {
      connection.metaData.getColumns(connection.catalog, connection.schema, table, column).use {
        it.next()
      }
    }

  fun isMySql(connection: Connection): Boolean {
    val product = connection.metaData.databaseProductName?.lowercase(Locale.ROOT).orEmpty()
    return product.contains("mysql") || product.contains("mariadb")
  }

  private fun informationSchema(connection: Connection, sql: String, vararg arguments: String) =
    connection.prepareStatement(sql).use { statement ->
      arguments.forEachIndexed { index, value -> statement.setString(index + 1, value) }
      statement.executeQuery().use { it.next() }
    }
}
