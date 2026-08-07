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
      val state = databaseManager.database.loadAiBuffer(shardPlayer.uuid) ?: return@runAsync
      val now = System.currentTimeMillis()
      val ageMillis = now - state.updatedAt
      val playerName = shardPlayer.player.name

      if (ageMillis < 0L) {
        logger.warning(
          "[PersistentBuffer] Skipped restore for $playerName: stored timestamp is in the future"
        )
        return@runAsync
      }

      if (!shardPlayer.player.isOnline) return@runAsync

      val labelStates = databaseManager.database.loadAiLabelBuffers(shardPlayer.uuid)

      if (ageMillis < configManager.persistentBufferDisconnectWindowMillis) {
        scheduler.runSync(shardPlayer.player) {
          if (!shardPlayer.player.isOnline) return@runSync
          aiCheck.restoreBuffer(state.buffer)
          for ((label, labelState) in labelStates) {
            aiCheck.restoreLabelBuffer(label, labelState.buffer)
          }
        }
        debugManager.log(
          DebugCategory.AI_PERSISTENT_BUFFER,
          "$playerName reconnected within disconnect window; buffer ${format(state.buffer)} " +
            "and ${labelStates.size} label buffer(s) kept",
        )
        return@runAsync
      }

      if (ageMillis > configManager.persistentBufferTtlMillis) {
        debugManager.log(
          DebugCategory.AI_PERSISTENT_BUFFER,
          "$playerName buffer expired (offline ${format(ageMillis / MILLIS_PER_HOUR)}h), discarded",
        )
        return@runAsync
      }

      val ageHours = ageMillis / MILLIS_PER_HOUR
      val decayed = state.buffer - configManager.persistentBufferDecayPerHour * ageHours
      val capped = min(decayed, configManager.persistentBufferCap)
      val finalBuffer = max(0.0, capped)

      scheduler.runSync(shardPlayer.player) {
        if (!shardPlayer.player.isOnline) return@runSync
        aiCheck.restoreBuffer(finalBuffer)
        for ((label, labelState) in labelStates) {
          aiCheck.restoreLabelBuffer(label, decayAndCap(labelState.buffer, ageHours))
        }
      }
      debugManager.log(
        DebugCategory.AI_PERSISTENT_BUFFER,
        "$playerName restored buffer ${format(state.buffer)} → ${format(finalBuffer)} (offline ${format(ageHours)}h)",
      )
    }
  }

  fun saveOnQuit(shardPlayer: ShardPlayer) {
    if (!configManager.persistentBufferEnabled) return
    val now = System.currentTimeMillis()
    val aiCheck = shardPlayer.checkManager.getCheck(AiCheck::class.java) ?: return

    val threshold = configManager.persistentBufferSaveThreshold
    if (aiCheck.buffer >= threshold) {
      databaseManager.database.saveAiBuffer(shardPlayer.uuid, aiCheck.buffer, now)
    }

    val labelBuffers = aiCheck.labelBufferSnapshot().filterValues { it >= threshold }
    if (labelBuffers.isNotEmpty()) {
      databaseManager.database.saveAiLabelBuffers(shardPlayer.uuid, labelBuffers, now)
    }
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
