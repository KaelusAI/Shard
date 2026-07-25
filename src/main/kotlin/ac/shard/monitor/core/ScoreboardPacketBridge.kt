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

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.score.ScoreFormat
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore
import java.util.Optional
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

data class ObjectiveSpec(
  val name: String,
  val slot: Int,
  val title: String,
  val legacyTitleFallback: String,
  val blankScoreText: String,
)

data class ScoreDisplay(val entryDisplay: String?, val scoreText: String?)

class ScoreboardPacketBridge(private val cache: ComponentCache) {
  fun supportsFancyText(viewer: Player): Boolean =
    serverSupportsFancyText() && clientVersion(viewer).isNewerThanOrEquals(ServerVersion.V_1_20_3)

  fun createObjective(viewer: Player, spec: ObjectiveSpec) {
    val fancy = supportsFancyText(viewer)
    val title = if (fancy) spec.title else spec.title.ifBlank { spec.legacyTitleFallback }
    val create =
      if (fancy) {
        WrapperPlayServerScoreboardObjective(
          spec.name,
          WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
          cache.component(title),
          WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
          ScoreFormat.fixedScore(cache.component(spec.blankScoreText)),
        )
      } else {
        WrapperPlayServerScoreboardObjective(
          spec.name,
          WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
          cache.component(title),
          WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
        )
      }
    sendPacket(viewer, create)
    sendPacket(viewer, WrapperPlayServerDisplayScoreboard(spec.slot, spec.name))
  }

  fun displayObjective(viewer: Player, name: String, slot: Int) {
    sendPacket(viewer, WrapperPlayServerDisplayScoreboard(slot, name))
  }

  fun removeObjective(viewer: Player, name: String) {
    sendPacket(
      viewer,
      WrapperPlayServerScoreboardObjective(
        name,
        WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
        Component.empty(),
        null,
      ),
    )
  }

  fun updateEntry(
    viewer: Player,
    objective: String,
    entry: String,
    score: Int,
    display: ScoreDisplay,
  ) {
    sendPacket(viewer, updateScorePacket(viewer, objective, entry, score, display))
  }

  fun removeEntry(viewer: Player, objective: String, entry: String) {
    val packet =
      if (supportsFancyText(viewer)) {
        WrapperPlayServerResetScore(entry, objective)
      } else {
        WrapperPlayServerUpdateScore(
          entry,
          WrapperPlayServerUpdateScore.Action.REMOVE_ITEM,
          objective,
          Optional.empty(),
        )
      }
    sendPacket(viewer, packet)
  }

  private fun updateScorePacket(
    viewer: Player,
    objective: String,
    entry: String,
    score: Int,
    display: ScoreDisplay,
  ): PacketWrapper<*> {
    if (!supportsFancyText(viewer)) {
      return WrapperPlayServerUpdateScore(
        entry,
        WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
        objective,
        Optional.of(score),
      )
    }
    val format =
      display.scoreText?.let { ScoreFormat.fixedScore(cache.component(it)) }
        ?: ScoreFormat.blankScore()
    return WrapperPlayServerUpdateScore(
      entry,
      WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
      objective,
      score,
      display.entryDisplay?.let { cache.component(it) },
      format,
    )
  }

  fun serverSupportsFancyText(): Boolean =
    PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_20_3)

  private fun clientVersion(viewer: Player): ServerVersion =
    PacketEvents.getAPI().playerManager.getClientVersion(viewer).toServerVersion()

  private fun sendPacket(viewer: Player, packet: PacketWrapper<*>) {
    PacketEvents.getAPI().playerManager.sendPacket(viewer, packet)
  }
}
