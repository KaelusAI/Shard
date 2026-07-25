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
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.score.ScoreFormat
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore
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

internal class ViewBelowNamePacketBridge(private val componentCache: ComponentCache) {
  fun supportsFancyText(): Boolean {
    return PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_20_3)
  }

  fun createObjective(viewer: Player, objectiveName: String, title: String, defaultText: String) {
    val objectiveTitle = resolveObjectiveTitle(title)
    val createObjective =
      if (supportsFancyText()) {
        WrapperPlayServerScoreboardObjective(
          objectiveName,
          WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
          componentCache.component(objectiveTitle),
          WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
          ScoreFormat.fixedScore(componentCache.component(defaultText)),
        )
      } else {
        WrapperPlayServerScoreboardObjective(
          objectiveName,
          WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
          componentCache.component(objectiveTitle),
          WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
        )
      }
    val displayObjective =
      WrapperPlayServerDisplayScoreboard(BELOW_NAME_DISPLAY_SLOT, objectiveName)
    sendPacket(viewer, createObjective)
    sendPacket(viewer, displayObjective)
  }

  fun displayObjective(viewer: Player, objectiveName: String) {
    sendPacket(viewer, WrapperPlayServerDisplayScoreboard(BELOW_NAME_DISPLAY_SLOT, objectiveName))
  }

  fun updateEntry(
    viewer: Player,
    objectiveName: String,
    targetName: String,
    text: String,
    score: Int,
  ) {
    val wrapper = createUpdateScorePacket(objectiveName, targetName, text, score)
    sendPacket(viewer, wrapper)
  }

  fun removeEntry(viewer: Player, objectiveName: String, targetName: String) {
    val wrapper = createRemoveScorePacket(objectiveName, targetName)
    sendPacket(viewer, wrapper)
  }

  fun removeObjective(viewer: Player, objectiveName: String) {
    val wrapper =
      WrapperPlayServerScoreboardObjective(
        objectiveName,
        WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
        Component.empty(),
        null,
      )
    sendPacket(viewer, wrapper)
  }

  private fun sendPacket(viewer: Player, packet: PacketWrapper<*>) {
    PacketEvents.getAPI().playerManager.sendPacket(viewer, packet)
  }

  private fun resolveObjectiveTitle(title: String): String {
    if (supportsFancyText()) {
      return title
    }
    return title.ifBlank { DEFAULT_LEGACY_BELOW_TITLE }
  }

  private fun createUpdateScorePacket(
    objectiveName: String,
    targetName: String,
    text: String,
    score: Int,
  ): PacketWrapper<*> {
    return if (supportsFancyText()) {
      WrapperPlayServerUpdateScore(
        targetName,
        WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
        objectiveName,
        score,
        null,
        ScoreFormat.fixedScore(componentCache.component(text)),
      )
    } else {
      WrapperPlayServerUpdateScore(
        targetName,
        WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
        objectiveName,
        java.util.Optional.of(score),
      )
    }
  }

  private fun createRemoveScorePacket(objectiveName: String, targetName: String): PacketWrapper<*> {
    return if (supportsFancyText()) {
      WrapperPlayServerResetScore(targetName, objectiveName)
    } else {
      WrapperPlayServerUpdateScore(
        targetName,
        WrapperPlayServerUpdateScore.Action.REMOVE_ITEM,
        objectiveName,
        java.util.Optional.empty(),
      )
    }
  }
}

private const val DEFAULT_LEGACY_BELOW_TITLE = "% AI"
