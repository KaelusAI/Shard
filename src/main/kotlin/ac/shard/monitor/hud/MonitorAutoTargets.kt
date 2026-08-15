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
package ac.shard.monitor.hud

import ac.shard.checks.impl.ai.AiCheck
import ac.shard.config.LocaleManager
import ac.shard.monitor.core.MonitorTargetMode
import ac.shard.player.PlayerDataManager
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.entity.Player

internal class MonitorAutoTargets(
  private val playerDataManager: PlayerDataManager,
  private val index: MonitorTargetIndex,
) {
  fun resolve(
    session: MonitorHudSession,
    viewerId: UUID,
    localeManager: LocaleManager,
  ): List<Player> {
    if (!session.targetMode.isAuto || session.autoCycles++ < session.config.auto.refreshCycles) {
      return if (session.targetMode.isAuto) {
        session.autoTargets.filter { it.isOnline }
      } else {
        watched(session)
      }
    }
    session.autoCycles = 0
    val picked = pick(session)
    val ids = picked.map { it.uniqueId }.toSet()
    session.targets.retain(ids, session.config.auto.lingerCycles)
    picked.forEach { session.trackTarget(it, targetTexts(localeManager, it.name)) }
    index.update(viewerId, ids)
    session.autoTargets = picked
    return picked
  }

  private fun watched(session: MonitorHudSession): List<Player> =
    session.targets.ids().mapNotNull { Bukkit.getPlayer(it) }.filter { it.isOnline }

  private fun pick(session: MonitorHudSession): List<Player> {
    val capacity = effectiveCapacity(session.outputs, session.config)
    val watching = session.targets.ids().toSet()
    val auto = session.config.auto
    return playerDataManager
      .getPlayers()
      .asSequence()
      .mapNotNull { shardPlayer ->
        val player = shardPlayer.player
        if (!player.isOnline) return@mapNotNull null
        Candidate(
          player = player,
          buffer = shardPlayer.checkManager.getCheck(AiCheck::class.java)?.buffer ?: 0.0,
          inCombat =
            shardPlayer.combat.hasAttacked &&
              shardPlayer.combat.ticksSinceAttack <= auto.combatTicks,
        )
      }
      .filter { qualifies(session, it, it.player.uniqueId in watching, auto) }
      .sortedWith(
        compareByDescending<Candidate> { it.buffer }
          .thenByDescending { it.inCombat }
          .thenBy { it.player.name }
      )
      .take(capacity)
      .map { it.player }
      .toList()
  }

  private fun qualifies(
    session: MonitorHudSession,
    candidate: Candidate,
    watching: Boolean,
    auto: MonitorAutoConfig,
  ): Boolean {
    val bar = if (watching) auto.suspiciousBuffer * auto.exitRatio else auto.suspiciousBuffer
    return when (session.targetMode) {
      MonitorTargetMode.SUSPICIOUS -> candidate.buffer > bar
      MonitorTargetMode.AUTO -> candidate.inCombat || candidate.buffer > bar
      else -> true
    }
  }

  private data class Candidate(val player: Player, val buffer: Double, val inCombat: Boolean)
}
