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
package ac.shard.monitor.core

import ac.shard.scheduler.SchedulerService
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective
import org.bukkit.entity.Player

class ScoreboardSlotObserver(
  private val scheduler: SchedulerService,
  private val registry: ScoreboardSlotRegistry,
) : PacketListenerAbstract(PacketListenerPriority.MONITOR) {
  override fun onPacketSend(event: PacketSendEvent) {
    if (registry.isIdle()) {
      return
    }
    val type = event.packetType
    if (
      type != PacketType.Play.Server.DISPLAY_SCOREBOARD &&
        type != PacketType.Play.Server.SCOREBOARD_OBJECTIVE
    ) {
      return
    }
    inspect(event, type == PacketType.Play.Server.DISPLAY_SCOREBOARD)
  }

  private fun inspect(event: PacketSendEvent, isDisplay: Boolean) {
    val viewer = event.getPlayer<Any>() as? Player ?: return
    val claims = registry.claimsFor(viewer.uniqueId) ?: return
    if (isDisplay) {
      inspectDisplay(event, viewer, claims)
    } else {
      inspectObjective(event, viewer, claims)
    }
  }

  private fun inspectDisplay(event: PacketSendEvent, viewer: Player, claims: List<SlotClaim>) {
    val wrapper = WrapperPlayServerDisplayScoreboard(event)
    val foreign = wrapper.scoreName
    if (foreign.isBlank()) {
      return
    }
    val claim = claims.firstOrNull { it.slot == wrapper.position && it.objective != foreign }
    claim?.let { defer(event, viewer, it, SlotLossReason.DISPLACED, foreign) }
  }

  private fun inspectObjective(event: PacketSendEvent, viewer: Player, claims: List<SlotClaim>) {
    val wrapper = WrapperPlayServerScoreboardObjective(event)
    if (wrapper.mode != WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
      return
    }
    val claim = claims.firstOrNull { it.objective == wrapper.name }
    claim?.let { defer(event, viewer, it, SlotLossReason.OBJECTIVE_REMOVED, wrapper.name) }
  }

  private fun defer(
    event: PacketSendEvent,
    viewer: Player,
    claim: SlotClaim,
    reason: SlotLossReason,
    foreignObjective: String,
  ) {
    event.tasksAfterSend.add(
      Runnable {
        scheduler.runSync(
          viewer,
          Runnable { claim.onLost.onSlotLost(viewer, reason, foreignObjective) },
        )
      }
    )
  }
}
