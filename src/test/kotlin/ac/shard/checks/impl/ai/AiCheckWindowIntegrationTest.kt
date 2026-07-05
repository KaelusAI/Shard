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

import ac.shard.Shard
import ac.shard.ai.AiResult
import ac.shard.ai.AiService
import ac.shard.alert.AlertManager
import ac.shard.checks.CheckManager
import ac.shard.config.ConfigManager
import ac.shard.damage.DamageProcessor
import ac.shard.debug.DebugManager
import ac.shard.entity.CompensatedEntities
import ac.shard.entity.PacketEntity
import ac.shard.entity.types.PacketEntitySelf
import ac.shard.player.ShardPlayer
import ac.shard.player.state.CombatState
import ac.shard.player.state.MovementState
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.data.PacketStateData
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test

class AiCheckWindowIntegrationTest {

  private class RecordingAiService : AiService {
    val requests = mutableListOf<Pair<FloatArray, Int>>()
    override val isEnabled: Boolean = true

    override fun request(features: FloatArray, count: Int): CompletableFuture<AiResult> {
      requests.add(features.copyOf(count * 2) to count)
      return CompletableFuture()
    }
  }

  private class SimState(
    val movement: MovementState,
    val combat: CombatState,
    val ridingHolder: Array<PacketEntity?>,
  )

  private class Fixture(
    val check: AiCheck,
    val aiService: RecordingAiService,
    val event: PacketReceiveEvent,
    val state: SimState,
    val pendingAsyncTasks: MutableList<Runnable>,
  ) {
    val combat: CombatState
      get() = state.combat

    val ridingHolder: Array<PacketEntity?>
      get() = state.ridingHolder

    fun sendPacket(deltaYaw: Float, deltaPitch: Float) {
      val movement = state.movement
      movement.lastYaw = movement.yaw
      movement.lastPitch = movement.pitch
      movement.yaw += deltaYaw
      movement.pitch += deltaPitch
      check.onPacketReceive(event)
    }
  }

  private fun createFixture(
    sequence: Int,
    step: Int,
    continuous: Boolean = false,
    runAsyncImmediately: Boolean = true,
  ): Fixture {
    val logger = mockk<Logger>(relaxed = true)
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.logger } returns logger

    val aiService = RecordingAiService()

    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.aiSequence } returns sequence
    every { configManager.aiStep } returns step
    every { configManager.aiContinuous } returns continuous
    every { configManager.isAiWorldGuardEnabled() } returns false
    every { configManager.isDebugEnabled() } returns false

    val packetStateData = PacketStateData()
    val player = mockk<Player>(relaxed = true)
    every { player.name } returns "TestPlayer"

    val checkManager = mockk<CheckManager>(relaxed = true)

    val ridingHolder = arrayOfNulls<PacketEntity>(1)
    val self = mockk<PacketEntitySelf>(relaxed = true)
    every { self.riding } answers { ridingHolder[0] }
    val compensatedEntities = mockk<CompensatedEntities>(relaxed = true)
    every { compensatedEntities.self } returns self

    val movement = MovementState()
    val combat = CombatState(0)

    val shardPlayer = mockk<ShardPlayer>(relaxed = true)
    every { shardPlayer.player } returns player
    every { shardPlayer.packetStateData } returns packetStateData
    every { shardPlayer.checkManager } returns checkManager
    every { shardPlayer.compensatedEntities } returns compensatedEntities
    every { shardPlayer.movement } returns movement
    every { shardPlayer.combat } returns combat

    val pendingAsyncTasks = mutableListOf<Runnable>()
    val scheduler = mockk<SchedulerService>(relaxed = true)
    every { scheduler.runAsync(any()) } answers
      {
        val task = firstArg<Runnable>()
        if (runAsyncImmediately) task.run() else pendingAsyncTasks.add(task)
        mockk(relaxed = true)
      }

    val event = mockk<PacketReceiveEvent>(relaxed = true)
    every { event.packetType } returns PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION

    val check =
      AiCheck(
        shardPlayer = shardPlayer,
        plugin = plugin,
        aiService = aiService,
        configManager = configManager,
        regionProvider = mockk<RegionProvider>(relaxed = true),
        alertManager = mockk<AlertManager>(relaxed = true),
        damageProcessor = mockk<DamageProcessor>(relaxed = true),
        debugManager = DebugManager(plugin, configManager),
        scheduler = scheduler,
      )

    return Fixture(
      check,
      aiService,
      event,
      SimState(movement, combat, ridingHolder),
      pendingAsyncTasks,
    )
  }

  @Test
  fun `TC3 sliding window stays chronological across a ring wrap`() {
    val fixture = createFixture(sequence = 3, step = 1)

    fixture.sendPacket(1f, 11f) // A
    fixture.sendPacket(2f, 12f) // B
    fixture.sendPacket(3f, 13f) // C -> window full, send [A,B,C]
    fixture.sendPacket(4f, 14f) // D -> send [B,C,D]
    fixture.sendPacket(5f, 15f) // E -> send [C,D,E]

    assertEquals(3, fixture.aiService.requests.size)
    assertEquals(listOf(1f, 11f, 2f, 12f, 3f, 13f), fixture.aiService.requests[0].first.toList())
    assertEquals(listOf(2f, 12f, 3f, 13f, 4f, 14f), fixture.aiService.requests[1].first.toList())
    assertEquals(listOf(3f, 13f, 4f, 14f, 5f, 15f), fixture.aiService.requests[2].first.toList())
  }

  @Test
  fun `TC5 riding mid-window discards the pre-mount ticks`() {
    val fixture = createFixture(sequence = 4, step = 1)

    fixture.sendPacket(1f, 11f) // A
    fixture.sendPacket(2f, 12f) // B
    fixture.sendPacket(3f, 13f) // C - no send yet (count=3 < 4)

    fixture.ridingHolder[0] = mockk(relaxed = true)
    fixture.sendPacket(0f, 0f) // mount packet: resets the window, no push
    fixture.ridingHolder[0] = null

    fixture.sendPacket(5f, 15f) // E
    fixture.sendPacket(6f, 16f) // F
    fixture.sendPacket(7f, 17f) // G - still no send (count=3 < 4)
    assertEquals(0, fixture.aiService.requests.size)

    fixture.sendPacket(8f, 18f) // H -> send [E,F,G,H], no A/B/C contamination

    assertEquals(1, fixture.aiService.requests.size)
    assertEquals(
      listOf(5f, 15f, 6f, 16f, 7f, 17f, 8f, 18f),
      fixture.aiService.requests[0].first.toList(),
    )
  }

  @Test
  fun `TC8 attack-gap reset drops the pre-gap ticks`() {
    val fixture = createFixture(sequence = 4, step = 1, continuous = false)

    fixture.sendPacket(1f, 11f) // A
    fixture.sendPacket(2f, 12f) // B - count=2, no send

    fixture.combat.ticksSinceAttack = 5
    fixture.sendPacket(0f, 0f)
    fixture.sendPacket(0f, 0f)
    assertEquals(0, fixture.aiService.requests.size)

    fixture.combat.ticksSinceAttack = 0
    fixture.sendPacket(5f, 15f) // E
    fixture.sendPacket(6f, 16f) // F
    fixture.sendPacket(7f, 17f) // G
    fixture.sendPacket(8f, 18f) // H -> send [E,F,G,H], no A/B contamination

    assertEquals(1, fixture.aiService.requests.size)
    assertEquals(
      listOf(5f, 15f, 6f, 16f, 7f, 17f, 8f, 18f),
      fixture.aiService.requests[0].first.toList(),
    )
  }

  @Test
  fun `TC14 in-flight snapshot is isolated from a concurrent reset`() {
    val fixture = createFixture(sequence = 4, step = 1, runAsyncImmediately = false)

    fixture.sendPacket(1f, 11f) // A
    fixture.sendPacket(2f, 12f) // B
    fixture.sendPacket(3f, 13f) // C
    fixture.sendPacket(4f, 14f) // D -> triggers sendData, async task deferred

    assertTrue(fixture.aiService.requests.isEmpty())
    assertEquals(1, fixture.pendingAsyncTasks.size)

    fixture.ridingHolder[0] = mockk(relaxed = true)
    fixture.sendPacket(0f, 0f)
    fixture.ridingHolder[0] = null
    fixture.sendPacket(9f, 19f) // E, into the fresh post-reset window

    fixture.pendingAsyncTasks.removeAt(0).run()

    assertEquals(1, fixture.aiService.requests.size)
    assertEquals(
      listOf(1f, 11f, 2f, 12f, 3f, 13f, 4f, 14f),
      fixture.aiService.requests[0].first.toList(),
    )
  }
}
