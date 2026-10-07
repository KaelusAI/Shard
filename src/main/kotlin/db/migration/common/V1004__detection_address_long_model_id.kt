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
class V1004__detection_address_long_model_id : BaseJavaMigration() {
  override fun migrate(context: Context) {
    val connection = context.connection
    val product = connection.metaData.databaseProductName?.lowercase(Locale.ROOT).orEmpty()
    val mysql = product.contains("mysql") || product.contains("mariadb")
    if (!mysql && !product.contains("postgres")) return
    for ((table, column, tail) in COLUMNS) {
      if (!columnExists(connection, table, column)) continue
      val sql =
        if (mysql) "ALTER TABLE $table MODIFY $column VARCHAR($ADDRESS_LENGTH) $tail"
        else "ALTER TABLE $table ALTER COLUMN $column TYPE VARCHAR($ADDRESS_LENGTH)"
      connection.createStatement().use { it.execute(sql) }
    }
  }

  private fun columnExists(connection: Connection, table: String, column: String): Boolean =
    MigrationSchema.columnExists(connection, table, column)

  private companion object {
    private const val ADDRESS_LENGTH = 129
    private val COLUMNS =
      listOf(
        Triple("ai_label_buffers", "label", "NOT NULL"),
        Triple("monitor_settings", "label_focus", "NOT NULL DEFAULT ''"),
      )
  }
}
