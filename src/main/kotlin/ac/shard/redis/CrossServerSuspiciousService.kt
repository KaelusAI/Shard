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
package ac.shard.redis

import ac.shard.checks.impl.ai.AiCheck
import ac.shard.config.ConfigManager
import ac.shard.platform.scheduler.TaskHandle
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.logging.Level
import java.util.logging.Logger

class CrossServerSuspiciousService(
  private val configManager: ConfigManager,
  private val redisManager: RedisManager,
  private val playerDataManager: PlayerDataManager,
  private val scheduler: SchedulerService,
  private val logger: Logger,
) {
  private val mapper = ObjectMapper()

  @Volatile private var enabled = false
  @Volatile private var refreshTask: TaskHandle? = null
  @Volatile
  var serverName: String = DEFAULT_SERVER_NAME
    private set

  private var keyPrefix = "$DEFAULT_CHANNEL:suspect"
  private var ttlSeconds = DEFAULT_TTL_SECONDS

  val isActive: Boolean
    get() = enabled

  fun start() {
    val config = configManager.config
    if (
      !config.getBoolean("network.enabled", false) ||
        !config.getBoolean("network.share.suspicious", true)
    ) {
      return
    }

    serverName = config.getString("network.name", DEFAULT_SERVER_NAME)
    val channel = config.getString("network.channel", DEFAULT_CHANNEL)
    keyPrefix = "$channel:suspect"
    ttlSeconds = config.getLong("network.suspicious-sync.ttl-seconds", DEFAULT_TTL_SECONDS)
    val refreshSeconds =
      config
        .getLong("network.suspicious-sync.refresh-seconds", DEFAULT_REFRESH_SECONDS)
        .coerceIn(1L, ttlSeconds.coerceAtLeast(1L))
    ttlSeconds = ttlSeconds.coerceAtLeast(refreshSeconds + 1L)

    redisManager.start()
    if (!redisManager.isAvailable) {
      logger.warning(
        "[CrossServer] suspicious-sync is enabled but Redis is unavailable; the list stays local."
      )
      return
    }

    enabled = true
    val periodTicks = refreshSeconds * TICKS_PER_SECOND
    refreshTask = scheduler.runTimer(::publishLocalSuspicious, periodTicks, periodTicks)
    logger.info(
      "[CrossServer] Sharing suspicious players as \"$serverName\" " +
        "(refresh ${refreshSeconds}s, ttl ${ttlSeconds}s)."
    )
  }

  private fun publishLocalSuspicious() {
    if (!enabled) return
    playerDataManager.getPlayers().forEach(::publishPlayer)
  }

  private fun publishPlayer(shardPlayer: ShardPlayer) {
    val check = shardPlayer.checkManager.getCheck(AiCheck::class.java)
    if (check == null || check.buffer <= 0.0) return
    val player = shardPlayer.player
    val payload =
      runCatching {
          mapper.writeValueAsString(
            SuspiciousSnapshot(
              server = serverName,
              uuid = shardPlayer.uuid.toString(),
              name = player.name,
              buffer = check.buffer,
              ping = player.ping,
              updatedAt = System.currentTimeMillis(),
            )
          )
        }
        .getOrNull() ?: return
    redisManager.setWithTtl("$keyPrefix:$serverName:${shardPlayer.uuid}", payload, ttlSeconds)
  }

  fun fetchRemote(): List<SuspiciousSnapshot> {
    if (!enabled) return emptyList()
    return redisManager
      .scanValues("$keyPrefix:*")
      .mapNotNull { raw ->
        runCatching { mapper.readValue(raw, SuspiciousSnapshot::class.java) }
          .onFailure { error ->
            logger.log(Level.FINE, "[CrossServer] Bad suspect payload.", error)
          }
          .getOrNull()
      }
      .filter { it.server != serverName }
  }

  fun shutdown() {
    enabled = false
    refreshTask?.cancel()
    refreshTask = null
  }

  private companion object {
    const val DEFAULT_SERVER_NAME = "server-1"
    const val DEFAULT_CHANNEL = "shard:alerts"
    const val DEFAULT_TTL_SECONDS = 30L
    const val DEFAULT_REFRESH_SECONDS = 10L
    const val TICKS_PER_SECOND = 20L
  }
}
