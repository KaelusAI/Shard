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
package ac.shard.checks.impl.ai

import ac.shard.entity.CompensatedEntities
import ac.shard.entity.PacketEntity
import ac.shard.player.ShardPlayer
import ac.shard.player.state.CombatState
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ActionManagerTest {

  private val targetId = 42

  private class Fixture(val manager: ActionManager, val combat: CombatState)

  private fun createFixture(targetIsPlayer: Boolean = true): Fixture {
    val target = mockk<PacketEntity>(relaxed = true)
    every { target.isPlayer } returns targetIsPlayer

    val compensatedEntities = mockk<CompensatedEntities>(relaxed = true)
    every { compensatedEntities.getEntity(targetId) } returns target

    val combat = CombatState(STALE_TICKS)
    val shardPlayer = mockk<ShardPlayer>(relaxed = true)
    every { shardPlayer.compensatedEntities } returns compensatedEntities
    every { shardPlayer.combat } returns combat

    return Fixture(ActionManager(shardPlayer), combat)
  }

  private fun interactEvent(action: WrapperPlayClientInteractEntity.InteractAction) {
    mockkConstructor(WrapperPlayClientInteractEntity::class)
    every { anyConstructed<WrapperPlayClientInteractEntity>().action } returns action
    every { anyConstructed<WrapperPlayClientInteractEntity>().entityId } returns targetId
  }

  private fun attackEvent() {
    mockkConstructor(WrapperPlayClientAttack::class)
    every { anyConstructed<WrapperPlayClientAttack>().entityId } returns targetId
  }

  private fun event(type: PacketType.Play.Client): PacketReceiveEvent {
    val event = mockk<PacketReceiveEvent>(relaxed = true)
    every { event.packetType } returns type
    return event
  }

  @BeforeEach
  fun stubPacketEvents() {
    mockkStatic(PacketEvents::class)
    every { PacketEvents.getAPI() } returns mockk(relaxed = true)
  }

  @AfterEach
  fun tearDown() {
    unmockkConstructor(WrapperPlayClientInteractEntity::class, WrapperPlayClientAttack::class)
    unmockkStatic(PacketEvents::class)
  }

  @Test
  fun `an attack inside INTERACT_ENTITY resets the counter`() {
    val fixture = createFixture()
    interactEvent(WrapperPlayClientInteractEntity.InteractAction.ATTACK)

    fixture.manager.onPacketReceive(event(PacketType.Play.Client.INTERACT_ENTITY))

    assertEquals(0, fixture.combat.ticksSinceAttack)
  }

  @Test
  fun `a standalone ATTACK packet resets the counter`() {
    val fixture = createFixture()
    attackEvent()

    fixture.manager.onPacketReceive(event(PacketType.Play.Client.ATTACK))

    assertEquals(0, fixture.combat.ticksSinceAttack)
  }

  @Test
  fun `an INTERACT_ENTITY that carries no attack leaves the counter alone`() {
    val fixture = createFixture()
    interactEvent(WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT)

    fixture.manager.onPacketReceive(event(PacketType.Play.Client.INTERACT_ENTITY))

    assertEquals(STALE_TICKS, fixture.combat.ticksSinceAttack)
  }

  @Test
  fun `attacking something other than a player leaves the counter alone`() {
    val fixture = createFixture(targetIsPlayer = false)
    attackEvent()

    fixture.manager.onPacketReceive(event(PacketType.Play.Client.ATTACK))

    assertEquals(STALE_TICKS, fixture.combat.ticksSinceAttack)
  }

  private companion object {
    const val STALE_TICKS = 7
  }
}
