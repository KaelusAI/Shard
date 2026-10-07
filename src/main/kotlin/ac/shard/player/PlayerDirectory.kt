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
package ac.shard.player

import ac.shard.Shard
import ac.shard.database.DatabaseManager
import ac.shard.database.KnownPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerDirectory(private val plugin: Shard, private val databaseManager: DatabaseManager) {
  private val recent = ConcurrentHashMap<String, Long>()

  fun remember(uuid: UUID, name: String, at: Long) {
    note(name, at)
    databaseManager.database.recordLogin(uuid, at)
    databaseManager.database.recordPlayer(uuid, name, at)
  }

  fun find(input: String): KnownPlayer? {
    val query = input.trim()
    val found = if (query.isEmpty()) null else online(query) ?: stored(query)
    found?.let { note(it.name, it.lastSeen) }
    return found
  }

  private fun online(name: String): KnownPlayer? =
    plugin.server.getPlayerExact(name)?.let {
      KnownPlayer(it.uniqueId, it.name, System.currentTimeMillis())
    }

  private fun stored(query: String): KnownPlayer? {
    val database = databaseManager.database
    val id = runCatching { UUID.fromString(query) }.getOrNull()
    return if (id != null) {
      database.findPlayer(id) ?: cached(id)
    } else {
      database.findPlayersByName(query, 1).firstOrNull() ?: cached(query)
    }
  }

  fun suggestions(): List<String> =
    (plugin.server.onlinePlayers.map { it.name } + recent.keys).distinct()

  private fun cached(id: UUID): KnownPlayer? {
    val offline = plugin.server.getOfflinePlayer(id)
    val name = offline.name ?: return null
    return KnownPlayer(id, name, offline.lastSeen)
  }

  private fun cached(name: String): KnownPlayer? {
    val offline = plugin.server.getOfflinePlayerIfCached(name) ?: return null
    return KnownPlayer(offline.uniqueId, offline.name ?: name, offline.lastSeen)
  }

  private fun note(name: String, at: Long) {
    recent[name] = at
    if (recent.size > MAX_RECENT) {
      recent.entries.minByOrNull { it.value }?.let { recent.remove(it.key) }
    }
  }

  private companion object {
    const val MAX_RECENT = 200
  }
}
