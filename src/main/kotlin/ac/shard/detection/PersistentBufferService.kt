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
package ac.shard.detection

import ac.shard.config.ConfigManager
import ac.shard.config.PersistentBufferSettings
import ac.shard.database.AiBufferState
import ac.shard.database.DatabaseManager
import ac.shard.debug.DebugCategory
import ac.shard.debug.DebugManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import java.util.Locale
import java.util.logging.Logger
import kotlin.math.max
import kotlin.math.min

private const val MILLIS_PER_HOUR = 3_600_000.0

class PersistentBufferService(
  private val configManager: ConfigManager,
  private val databaseManager: DatabaseManager,
  private val scheduler: SchedulerService,
  private val debugManager: DebugManager,
  private val logger: Logger,
) {
  fun restoreOnLogin(shardPlayer: ShardPlayer, onReady: () -> Unit) {
    val settings = configManager.settings.buffer
    if (!settings.enabled) {
      onReady()
      return
    }
    val aiCheck = shardPlayer.detection

    scheduler.runAsync {
      val stored = databaseManager.isAvailable
      val restored = restorable(shardPlayer, settings)
      shardPlayer.buffersLoaded = stored && databaseManager.isAvailable
      val player = shardPlayer.playerOrNull
      if (player == null || !player.isOnline) return@runAsync
      if (restored.isEmpty()) {
        onReady()
        return@runAsync
      }
      scheduler.runSync(player) {
        if (!player.isOnline) return@runSync
        for ((label, value) in restored) {
          aiCheck.restoreLabelBuffer(label, value)
        }
        onReady()
      }
      debugManager.log(
        DebugCategory.AI_PERSISTENT_BUFFER,
        "${player.name} restored " +
          restored.entries.joinToString(", ") { "${it.key}=${format(it.value)}" },
      )
    }
  }

  private fun restorable(
    shardPlayer: ShardPlayer,
    settings: PersistentBufferSettings,
  ): Map<String, Double> {
    val database = databaseManager.database
    val scalar = database.loadAiBuffer(shardPlayer.uuid)
    val labelStates = database.loadAiLabelBuffers(shardPlayer.uuid)
    val now = System.currentTimeMillis()
    val name = shardPlayer.name
    val restored = LinkedHashMap<String, Double>()
    for ((label, state) in labelStates) {
      surviving(state, now, name, settings)?.let { restored[label] = it }
    }
    if (labelStates.isEmpty() && scalar != null) {
      surviving(scalar, now, name, settings)?.let {
        restored[PlayerInference.UNATTRIBUTED_LABEL] = it
      }
    }
    if (restored.isEmpty() && (scalar != null || labelStates.isNotEmpty())) {
      debugManager.log(
        DebugCategory.AI_PERSISTENT_BUFFER,
        "$name had nothing left to restore (expired or decayed to zero)",
      )
    }
    return restored
  }

  private fun surviving(
    state: AiBufferState,
    now: Long,
    playerName: String,
    settings: PersistentBufferSettings,
  ): Double? {
    val ageMillis = now - state.updatedAt
    return when {
      ageMillis < 0L -> {
        logger.warning(
          "[PersistentBuffer] Skipped restore for $playerName: stored timestamp is in the future"
        )
        null
      }
      ageMillis < settings.disconnectWindowMillis -> state.buffer
      ageMillis > settings.ttlMillis -> null
      else -> decayAndCap(state.buffer, ageMillis / MILLIS_PER_HOUR, settings).takeIf { it > 0.0 }
    }
  }

  fun saveOnQuit(shardPlayer: ShardPlayer) {
    val settings = configManager.settings.buffer
    if (!settings.enabled || !shardPlayer.buffersRestored || !shardPlayer.buffersLoaded) return
    val now = System.currentTimeMillis()
    val aiCheck = shardPlayer.detection

    val threshold = settings.saveThreshold
    if (aiCheck.buffer >= threshold) {
      databaseManager.database.saveAiBuffer(shardPlayer.uuid, aiCheck.buffer, now)
    } else {
      databaseManager.database.saveAiBuffer(shardPlayer.uuid, 0.0, 0L)
    }

    val labelBuffers = aiCheck.labelBufferSnapshot().filterValues { it >= threshold }
    databaseManager.database.saveAiLabelBuffers(shardPlayer.uuid, labelBuffers, now)
  }

  private fun decayAndCap(
    saved: Double,
    ageHours: Double,
    settings: PersistentBufferSettings,
  ): Double {
    val decayed = saved - settings.decayPerHour * ageHours
    return max(0.0, min(decayed, settings.cap))
  }

  private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
