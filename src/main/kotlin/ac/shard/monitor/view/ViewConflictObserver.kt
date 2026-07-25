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

import ac.shard.scheduler.SchedulerService
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import java.util.UUID
import org.bukkit.entity.Player

internal class ViewConflictObserver(
  private val scheduler: SchedulerService,
  private val coordinator: ViewSessionCoordinator,
  private val belowNameConflicts: ViewBelowNameConflictCoordinator,
  private val viewerResolver: (UUID) -> Player?,
) : PacketListenerAbstract(PacketListenerPriority.MONITOR) {
  override fun onPacketSend(event: PacketSendEvent) {
    val viewer = event.getPlayer<Any>() as? Player ?: return
    val session = coordinator.session(viewer.uniqueId) ?: return

    when (event.packetType) {
      PacketType.Play.Server.DISPLAY_SCOREBOARD -> observeDisplayScoreboard(event, viewer, session)
      PacketType.Play.Server.SCOREBOARD_OBJECTIVE ->
        observeScoreboardObjective(event, viewer, session)
      PacketType.Play.Server.TEAMS -> observeTeams(event, session)
    }
  }

  private fun observeDisplayScoreboard(
    event: PacketSendEvent,
    viewer: Player,
    session: ViewSession,
  ) {
    if (session.usesBelowName()) {
      val objectiveName = session.belowObjectiveName
      if (objectiveName != null) {
        val wrapper = WrapperPlayServerDisplayScoreboard(event)
        val shouldReassert =
          wrapper.position == BELOW_NAME_DISPLAY_SLOT &&
            wrapper.scoreName.isNotBlank() &&
            wrapper.scoreName != objectiveName
        if (shouldReassert) {
          event.tasksAfterSend.add(
            Runnable {
              scheduler.runSync(
                viewer,
                Runnable {
                  belowNameConflicts.reassertDisplay(
                    viewer.uniqueId,
                    wrapper.scoreName,
                    viewerResolver,
                  )
                },
              )
            }
          )
        }
      }
    }
  }

  private fun observeScoreboardObjective(
    event: PacketSendEvent,
    viewer: Player,
    session: ViewSession,
  ) {
    if (session.usesBelowName()) {
      val objectiveName = session.belowObjectiveName
      if (objectiveName != null) {
        val wrapper = WrapperPlayServerScoreboardObjective(event)
        val shouldRecreate =
          wrapper.name == objectiveName &&
            wrapper.mode == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE
        if (shouldRecreate) {
          event.tasksAfterSend.add(
            Runnable {
              scheduler.runSync(
                viewer,
                Runnable {
                  belowNameConflicts.recreateObjective(
                    viewer.uniqueId,
                    objectiveName,
                    viewerResolver,
                  )
                },
              )
            }
          )
        }
      }
    }
  }

  private fun observeTeams(event: PacketSendEvent, session: ViewSession) {
    if (session.placement != ViewPlacement.ABOVE_NAME) {
      return
    }

    val wrapper = WrapperPlayServerTeams(event)
    if (wrapper.teamName.startsWith("slv_")) {
      return
    }

    when (wrapper.teamMode) {
      WrapperPlayServerTeams.TeamMode.CREATE,
      WrapperPlayServerTeams.TeamMode.ADD_ENTITIES -> {
        for (playerName in wrapper.players) {
          val targetId = session.targetIdByName(playerName) ?: continue
          session.targetTeams[targetId]?.markRebindNeeded()
        }
      }
      else -> Unit
    }
  }
}
