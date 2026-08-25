/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
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

import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

data class Violation(
  val serverName: String,
  val playerUUID: UUID,
  val playerName: String,
  val checkName: String,
  val verbose: String,
  val vl: Int,
  val createdAt: Instant,
  val aiBuffer: Double? = null,
  val mitigationScore: Double? = null,
  val windows: Long? = null,
  val highWindows: Long? = null,
  val trail: ByteArray = ByteArray(0),
) {
  companion object {
    @Throws(SQLException::class)
    fun fromResultSet(resultSet: ResultSet): List<Violation> {
      val violations = ArrayList<Violation>()
      while (resultSet.next()) {
        val server = resultSet.getString("server")
        val player = UUID.fromString(resultSet.getString("uuid"))
        val playerName = resultSet.getString("player_name")

        val checkName = resultSet.getString("check_name")
        val verbose = resultSet.getString("verbose")
        val vl = resultSet.getInt("vl")
        val createdAt = Instant.ofEpochMilli(resultSet.getLong("created_at"))
        violations.add(Violation(server, player, playerName, checkName, verbose, vl, createdAt))
      }
      return violations
    }
  }
}
