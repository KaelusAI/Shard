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
package ac.shard.redis

import ac.shard.checks.CheckManager
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.config.ConfigManager
import ac.shard.config.ConfigView
import ac.shard.mitigation.MitigationState
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class CrossServerSuspiciousServiceTest {
  private val mapper = ObjectMapper()

  private val enabledYaml =
    """
    network:
      enabled: true
      name: "Lobby"
      channel: "test:alerts"
      share:
        suspicious: true
      suspicious-sync:
        ttl-seconds: 30
        refresh-seconds: 10
    """
      .trimIndent()

  @Test
  fun `payload round-trips through jackson`() {
    val original = SuspiciousSnapshot("Lobby", UUID.randomUUID().toString(), "Notch", 27.5, 42, 1L)

    val restored =
      mapper.readValue(mapper.writeValueAsString(original), SuspiciousSnapshot::class.java)

    assertEquals(original, restored)
  }

  @Test
  fun `publishes only suspicious players to redis when enabled`() {
    val redis = mockk<RedisManager>(relaxed = true)
    every { redis.isAvailable } returns true
    val scheduler = mockk<SchedulerService>(relaxed = true)
    val refresh = slot<Runnable>()
    every { scheduler.runTimer(capture(refresh), any(), any()) } returns mockk(relaxed = true)
    val suspect = UUID.randomUUID()
    val playerData = mockk<PlayerDataManager>()
    every { playerData.getPlayers() } returns
      listOf(player(suspect, "Cheater", 30.0, 50), player(UUID.randomUUID(), "Calm", 0.0, 40))
    val keys = mutableListOf<String>()
    every { redis.setWithTtl(capture(keys), any(), any()) } just runs

    val service = service(enabledYaml, redis, scheduler, playerData)
    service.start()
    refresh.captured.run() // one refresh cycle

    assertTrue(service.isActive)
    assertEquals(1, keys.size, "only the player with buffer > 0 should be published")
    assertTrue(keys.single().endsWith(":Lobby:$suspect"))
  }

  @Test
  fun `does not touch redis when suspicious-sync is disabled`() {
    val redis = mockk<RedisManager>(relaxed = true)
    val scheduler = mockk<SchedulerService>(relaxed = true)
    val service =
      service(
        "network:\n  enabled: true\n  share:\n    suspicious: false\n",
        redis,
        scheduler,
        mockk(relaxed = true),
      )

    service.start()

    assertFalse(service.isActive)
    verify(exactly = 0) { redis.start() }
    verify(exactly = 0) { scheduler.runTimer(any(), any(), any()) }
    verify(exactly = 0) { redis.setWithTtl(any(), any(), any()) }
  }

  @Test
  fun `fetchRemote returns other servers and ignores own and malformed entries`() {
    val redis = mockk<RedisManager>(relaxed = true)
    every { redis.isAvailable } returns true
    every { redis.scanValues(any()) } returns
      listOf(
        mapper.writeValueAsString(SuspiciousSnapshot("Lobby", "u1", "Self", 10.0, 5, 1L)),
        mapper.writeValueAsString(SuspiciousSnapshot("PvP", "u2", "Remote", 22.0, 7, 2L)),
        "not json",
      )
    val service = service(enabledYaml, redis, mockk(relaxed = true), mockk(relaxed = true))
    service.start()

    val remote = service.fetchRemote()

    assertEquals(1, remote.size)
    assertEquals("PvP", remote.single().server)
    assertEquals("Remote", remote.single().name)
  }

  private fun player(uuid: UUID, name: String, buffer: Double, ping: Int): ShardPlayer {
    val check = mockk<AiCheck>()
    every { check.buffer } returns buffer
    val bukkitPlayer = mockk<Player>()
    every { bukkitPlayer.name } returns name
    every { bukkitPlayer.ping } returns ping
    val checkManager = mockk<CheckManager>()
    every { checkManager.getCheck(AiCheck::class.java) } returns check
    val shardPlayer = mockk<ShardPlayer>()
    every { shardPlayer.checkManager } returns checkManager
    every { shardPlayer.uuid } returns uuid
    every { shardPlayer.player } returns bukkitPlayer
    every { shardPlayer.mitigation } returns MitigationState()
    return shardPlayer
  }

  private fun service(
    yaml: String,
    redis: RedisManager,
    scheduler: SchedulerService,
    playerData: PlayerDataManager,
  ): CrossServerSuspiciousService {
    val configManager = mockk<ConfigManager>()
    val loader = YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build()
    every { configManager.config } returns ConfigView(loader.load())
    return CrossServerSuspiciousService(
      configManager,
      redis,
      playerData,
      scheduler,
      Logger.getLogger("suspicious-test"),
    )
  }
}
