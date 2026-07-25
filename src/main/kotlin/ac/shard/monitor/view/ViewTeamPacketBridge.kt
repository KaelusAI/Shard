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

import ac.shard.monitor.core.ComponentCache
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

internal class ViewTeamPacketBridge(private val componentCache: ComponentCache) {
  fun createTeam(viewer: Player, teamName: String, playerName: String, rendered: RenderedTag) {
    val wrapper =
      WrapperPlayServerTeams(
        teamName,
        WrapperPlayServerTeams.TeamMode.CREATE,
        createTeamInfo(rendered),
        listOf(playerName),
      )
    sendPacket(viewer, wrapper)
  }

  fun updateTeam(viewer: Player, teamName: String, rendered: RenderedTag) {
    val wrapper =
      WrapperPlayServerTeams(
        teamName,
        WrapperPlayServerTeams.TeamMode.UPDATE,
        createTeamInfo(rendered),
        emptyList(),
      )
    sendPacket(viewer, wrapper)
  }

  fun rebindEntity(viewer: Player, teamName: String, playerName: String) {
    val wrapper =
      WrapperPlayServerTeams(
        teamName,
        WrapperPlayServerTeams.TeamMode.ADD_ENTITIES,
        null as WrapperPlayServerTeams.ScoreBoardTeamInfo?,
        listOf(playerName),
      )
    sendPacket(viewer, wrapper)
  }

  fun removeTeam(viewer: Player, teamName: String) {
    val wrapper =
      WrapperPlayServerTeams(
        teamName,
        WrapperPlayServerTeams.TeamMode.REMOVE,
        null as WrapperPlayServerTeams.ScoreBoardTeamInfo?,
        emptyList<String>(),
      )
    sendPacket(viewer, wrapper)
  }

  private fun createTeamInfo(rendered: RenderedTag): WrapperPlayServerTeams.ScoreBoardTeamInfo {
    return WrapperPlayServerTeams.ScoreBoardTeamInfo(
      Component.empty(),
      componentCache.component(rendered.prefix),
      componentCache.component(rendered.suffix),
      WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
      WrapperPlayServerTeams.CollisionRule.ALWAYS,
      NamedTextColor.WHITE,
      WrapperPlayServerTeams.OptionData.NONE,
    )
  }

  private fun sendPacket(viewer: Player, packet: PacketWrapper<*>) {
    PacketEvents.getAPI().playerManager.sendPacket(viewer, packet)
  }
}
