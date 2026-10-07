/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
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
package ac.shard.packet

import ac.shard.player.ShardPlayer
import ac.shard.player.state.TrackingState
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.item.type.ItemType
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems

internal object InventoryTracking {
  private const val HOTBAR_START_SLOT = 36
  private const val OFFHAND_SLOT = 45
  private const val DEFAULT_ATTACK_SPEED = 4.0f

  fun onSetSlot(event: PacketSendEvent, player: ShardPlayer) {
    val wrapper = WrapperPlayServerSetSlot(event)
    if (wrapper.windowId != 0) return
    val tracking = player.tracking
    val hotbarIndex = wrapper.slot - HOTBAR_START_SLOT
    if (wrapper.slot == OFFHAND_SLOT) {
      tracking.offhandItem = wrapper.item.type
      tracking.updateActiveItem()
    } else if (hotbarIndex in 0 until TrackingState.HOTBAR_SIZE) {
      tracking.hotbarItems[hotbarIndex] = wrapper.item.type
      tracking.updateActiveItem()
      refreshAttackSpeed(player)
    }
  }

  fun onWindowItems(event: PacketSendEvent, player: ShardPlayer) {
    val wrapper = WrapperPlayServerWindowItems(event)
    if (wrapper.windowId != 0) return
    val items = wrapper.items
    val tracking = player.tracking
    for (i in 0 until TrackingState.HOTBAR_SIZE) {
      val slot = HOTBAR_START_SLOT + i
      if (slot < items.size) tracking.hotbarItems[i] = items[slot].type
    }
    if (OFFHAND_SLOT < items.size) tracking.offhandItem = items[OFFHAND_SLOT].type
    tracking.updateActiveItem()
    refreshAttackSpeed(player)
  }

  fun refreshAttackSpeed(player: ShardPlayer) {
    val tracking = player.tracking
    tracking.attackSpeed = itemAttackSpeed(tracking.hotbarItems.getOrNull(tracking.heldSlot))
  }

  @Suppress("MagicNumber")
  private fun itemAttackSpeed(item: ItemType?): Float =
    when (item) {
      ItemTypes.WOODEN_SWORD,
      ItemTypes.STONE_SWORD,
      ItemTypes.IRON_SWORD,
      ItemTypes.GOLDEN_SWORD,
      ItemTypes.DIAMOND_SWORD,
      ItemTypes.NETHERITE_SWORD -> 1.6f
      ItemTypes.WOODEN_AXE,
      ItemTypes.STONE_AXE -> 0.8f
      ItemTypes.IRON_AXE -> 0.9f
      ItemTypes.GOLDEN_AXE,
      ItemTypes.DIAMOND_AXE,
      ItemTypes.NETHERITE_AXE -> 1.0f
      ItemTypes.WOODEN_PICKAXE,
      ItemTypes.STONE_PICKAXE,
      ItemTypes.IRON_PICKAXE,
      ItemTypes.GOLDEN_PICKAXE,
      ItemTypes.DIAMOND_PICKAXE,
      ItemTypes.NETHERITE_PICKAXE -> 1.2f
      ItemTypes.WOODEN_SHOVEL,
      ItemTypes.STONE_SHOVEL,
      ItemTypes.IRON_SHOVEL,
      ItemTypes.GOLDEN_SHOVEL,
      ItemTypes.DIAMOND_SHOVEL,
      ItemTypes.NETHERITE_SHOVEL -> 1.0f
      ItemTypes.TRIDENT -> 1.1f
      else -> DEFAULT_ATTACK_SPEED
    }
}
