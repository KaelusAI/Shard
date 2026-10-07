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
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import java.util.UUID
import org.bukkit.Server
import org.bukkit.entity.Player

internal class ViewTrackingObserver(
  private val scheduler: SchedulerService,
  private val coordinator: ViewSessionCoordinator,
  private val server: Server,
  private val viewerResolver: (UUID) -> Player?,
) : PacketListenerAbstract(PacketListenerPriority.MONITOR) {
  override fun onPacketSend(event: PacketSendEvent) {
    val viewer = event.getPlayer<Any>() as? Player ?: return
    if (coordinator.session(viewer.uniqueId) == null) {
      return
    }

    when (event.packetType) {
      PacketType.Play.Server.SPAWN_PLAYER -> {
        val spawn = WrapperPlayServerSpawnPlayer(event)
        observeSpawnPlayer(event, viewer, spawn.uuid, spawn.entityId)
      }
      PacketType.Play.Server.SPAWN_ENTITY -> {
        val spawn = WrapperPlayServerSpawnEntity(event)
        val uuid = spawn.uuid.orElse(null)
        if (spawn.entityType == EntityTypes.PLAYER && uuid != null) {
          observeSpawnPlayer(event, viewer, uuid, spawn.entityId)
        }
      }
      PacketType.Play.Server.SET_PASSENGERS -> observePassengers(event, viewer)
      PacketType.Play.Server.RESPAWN ->
        event.tasksAfterSend.add(Runnable { coordinator.session(viewer.uniqueId)?.clientReset() })
      PacketType.Play.Server.DESTROY_ENTITIES -> observeDestroyEntities(event, viewer)
    }
  }

  private fun observePassengers(event: PacketSendEvent, viewer: Player) {
    val packet = WrapperPlayServerSetPassengers(event)
    val passengers =
      coordinator.passengersWithDisplay(viewer.uniqueId, packet.entityId, packet.passengers)
        ?: return
    packet.passengers = passengers
    event.markForReEncode(true)
  }

  private fun observeSpawnPlayer(
    event: PacketSendEvent,
    viewer: Player,
    targetId: UUID,
    entityId: Int,
  ) {
    if (targetId == viewer.uniqueId) {
      return
    }
    event.tasksAfterSend.add(
      Runnable { coordinator.session(viewer.uniqueId)?.clientSpawned(entityId) }
    )

    event.tasksAfterSend.add(
      Runnable {
        scheduler.runSync(
          viewer,
          Runnable {
            val activeViewer = viewerResolver(viewer.uniqueId) ?: return@Runnable
            val target = server.getPlayer(targetId) ?: return@Runnable
            if (isTrackableViewTarget(activeViewer, target)) {
              coordinator.trackTarget(activeViewer.uniqueId, target)
            }
          },
        )
      }
    )
  }

  private fun observeDestroyEntities(event: PacketSendEvent, viewer: Player) {
    val entityIds = WrapperPlayServerDestroyEntities(event).entityIds.copyOf()
    if (entityIds.isEmpty()) {
      return
    }
    coordinator.session(viewer.uniqueId)?.clientDestroyed(entityIds)

    event.tasksAfterSend.add(
      Runnable {
        scheduler.runSync(
          viewer,
          Runnable {
            val activeViewer = viewerResolver(viewer.uniqueId) ?: return@Runnable
            coordinator.removeTrackedEntities(activeViewer.uniqueId, entityIds)
          },
        )
      }
    )
  }
}
