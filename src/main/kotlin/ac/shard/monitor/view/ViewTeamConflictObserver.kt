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
package ac.shard.monitor.view

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import org.bukkit.entity.Player

internal class ViewTeamConflictObserver(private val coordinator: ViewSessionCoordinator) :
  PacketListenerAbstract(PacketListenerPriority.MONITOR) {
  override fun onPacketSend(event: PacketSendEvent) {
    if (event.packetType != PacketType.Play.Server.TEAMS) {
      return
    }
    val viewer = event.getPlayer<Any>() as? Player ?: return
    inspect(event, viewer)
  }

  private fun inspect(event: PacketSendEvent, viewer: Player) {
    val session = coordinator.session(viewer.uniqueId) ?: return
    if (session.placement != ViewPlacement.ABOVE_NAME) {
      return
    }
    markRebinds(WrapperPlayServerTeams(event), session)
  }

  private fun markRebinds(wrapper: WrapperPlayServerTeams, session: ViewSession) {
    if (wrapper.teamName.startsWith(TEAM_PREFIX)) {
      return
    }
    val mode = wrapper.teamMode
    if (
      mode != WrapperPlayServerTeams.TeamMode.CREATE &&
        mode != WrapperPlayServerTeams.TeamMode.ADD_ENTITIES
    ) {
      return
    }
    for (playerName in wrapper.players) {
      val targetId = session.targetIdByName(playerName) ?: continue
      session.targetTeams[targetId]?.markRebindNeeded()
    }
  }
}
