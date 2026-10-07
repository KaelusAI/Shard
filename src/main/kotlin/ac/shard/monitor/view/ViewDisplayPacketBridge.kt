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
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.bukkit.entity.Player

internal data class DisplayStyle(
  val offset: Float,
  val background: Int?,
  val shadow: Boolean,
  val seeThrough: Boolean,
  val lineWidth: Int,
)

internal class ViewDisplayPacketBridge(private val componentCache: ComponentCache) {
  fun supports(viewer: Player): Boolean {
    val api = PacketEvents.getAPI()
    return api.serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_20_2) &&
      api.playerManager
        .getClientVersion(viewer)
        .toServerVersion()
        .isNewerThanOrEquals(ServerVersion.V_1_20_2)
  }

  fun nextId(): Int = IDS.getAndDecrement()

  fun spawn(viewer: Player, displayId: Int, target: Player, style: DisplayStyle, text: String) {
    val at = target.location
    send(
      viewer,
      WrapperPlayServerSpawnEntity(
        displayId,
        Optional.of(UUID.randomUUID()),
        EntityTypes.TEXT_DISPLAY,
        Vector3d(at.x, at.y + target.height, at.z),
        0f,
        0f,
        0f,
        0,
        Optional.empty(),
      ),
    )
    send(viewer, WrapperPlayServerEntityMetadata(displayId, metadata(style, text)))
  }

  fun text(viewer: Player, displayId: Int, text: String) {
    send(
      viewer,
      WrapperPlayServerEntityMetadata(
        displayId,
        listOf(EntityData(TEXT, EntityDataTypes.ADV_COMPONENT, componentCache.component(text))),
      ),
    )
  }

  fun mount(viewer: Player, vehicleId: Int, passengers: IntArray) {
    send(viewer, WrapperPlayServerSetPassengers(vehicleId, passengers))
  }

  fun destroy(viewer: Player, displayId: Int) {
    send(viewer, WrapperPlayServerDestroyEntities(displayId))
  }

  private fun metadata(style: DisplayStyle, text: String): List<EntityData<*>> {
    var flags = 0
    if (style.shadow) flags = flags or FLAG_SHADOW
    if (style.seeThrough) flags = flags or FLAG_SEE_THROUGH
    if (style.background == null) flags = flags or FLAG_DEFAULT_BACKGROUND
    return listOfNotNull(
      EntityData(TRANSLATION, EntityDataTypes.VECTOR3F, Vector3f(0f, style.offset, 0f)),
      EntityData(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
      EntityData(TEXT, EntityDataTypes.ADV_COMPONENT, componentCache.component(text)),
      EntityData(LINE_WIDTH, EntityDataTypes.INT, style.lineWidth),
      style.background?.let { EntityData(BACKGROUND, EntityDataTypes.INT, it) },
      EntityData(STYLE_FLAGS, EntityDataTypes.BYTE, flags.toByte()),
    )
  }

  private fun send(viewer: Player, packet: PacketWrapper<*>) {
    PacketEvents.getAPI().playerManager.sendPacket(viewer, packet)
  }

  private companion object {
    val IDS = AtomicInteger(-1)
    const val TRANSLATION = 11
    const val BILLBOARD = 15
    const val TEXT = 23
    const val LINE_WIDTH = 24
    const val BACKGROUND = 25
    const val STYLE_FLAGS = 27
    const val BILLBOARD_CENTER: Byte = 3
    const val FLAG_SHADOW = 0x01
    const val FLAG_SEE_THROUGH = 0x02
    const val FLAG_DEFAULT_BACKGROUND = 0x04
  }
}
