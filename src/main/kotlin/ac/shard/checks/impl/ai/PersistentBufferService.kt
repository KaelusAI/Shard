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
package ac.shard.checks.impl.ai

import ac.shard.config.ConfigManager
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
  fun restoreOnLogin(shardPlayer: ShardPlayer) {
    if (!configManager.persistentBufferEnabled) return
    val aiCheck = shardPlayer.checkManager.getCheck(AiCheck::class.java) ?: return

    scheduler.runAsync {
      val scalar = databaseManager.database.loadAiBuffer(shardPlayer.uuid)
      val labelStates = databaseManager.database.loadAiLabelBuffers(shardPlayer.uuid)
      if (scalar == null && labelStates.isEmpty()) return@runAsync
      if (!shardPlayer.player.isOnline) return@runAsync

      val now = System.currentTimeMillis()
      val playerName = shardPlayer.player.name
      val restored = LinkedHashMap<String, Double>()

      for ((label, state) in labelStates) {
        surviving(state, now, playerName)?.let { restored[label] = it }
      }

      if (labelStates.isEmpty() && scalar != null) {
        surviving(scalar, now, playerName)?.let { restored[AiCheck.UNATTRIBUTED_LABEL] = it }
      }

      if (restored.isEmpty()) {
        debugManager.log(
          DebugCategory.AI_PERSISTENT_BUFFER,
          "$playerName had nothing left to restore (expired or decayed to zero)",
        )
        return@runAsync
      }

      scheduler.runSync(shardPlayer.player) {
        if (!shardPlayer.player.isOnline) return@runSync
        for ((label, value) in restored) {
          aiCheck.restoreLabelBuffer(label, value)
        }
      }
      debugManager.log(
        DebugCategory.AI_PERSISTENT_BUFFER,
        "$playerName restored " +
          restored.entries.joinToString(", ") { "${it.key}=${format(it.value)}" },
      )
    }
  }

  private fun surviving(state: AiBufferState, now: Long, playerName: String): Double? {
    val ageMillis = now - state.updatedAt
    return when {
      ageMillis < 0L -> {
        logger.warning(
          "[PersistentBuffer] Skipped restore for $playerName: stored timestamp is in the future"
        )
        null
      }
      ageMillis < configManager.persistentBufferDisconnectWindowMillis -> state.buffer
      ageMillis > configManager.persistentBufferTtlMillis -> null
      else -> decayAndCap(state.buffer, ageMillis / MILLIS_PER_HOUR).takeIf { it > 0.0 }
    }
  }

  fun saveOnQuit(shardPlayer: ShardPlayer) {
    if (!configManager.persistentBufferEnabled) return
    val now = System.currentTimeMillis()
    val aiCheck = shardPlayer.checkManager.getCheck(AiCheck::class.java) ?: return

    val threshold = configManager.persistentBufferSaveThreshold
    if (aiCheck.buffer >= threshold) {
      databaseManager.database.saveAiBuffer(shardPlayer.uuid, aiCheck.buffer, now)
    } else {
      databaseManager.database.saveAiBuffer(shardPlayer.uuid, 0.0, 0L)
    }

    val labelBuffers = aiCheck.labelBufferSnapshot().filterValues { it >= threshold }
    databaseManager.database.saveAiLabelBuffers(shardPlayer.uuid, labelBuffers, now)
  }

  fun saveOnShutdown(shardPlayer: ShardPlayer) {
    saveOnQuit(shardPlayer)
  }

  private fun decayAndCap(saved: Double, ageHours: Double): Double {
    val decayed = saved - configManager.persistentBufferDecayPerHour * ageHours
    return max(0.0, min(decayed, configManager.persistentBufferCap))
  }

  private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
