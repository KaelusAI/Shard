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
package ac.shard.player

import ac.shard.Shard
import ac.shard.alert.AlertManager
import ac.shard.api.event.ShardEventBus
import ac.shard.checks.CheckManager
import ac.shard.checks.impl.ai.DataCollectorManager
import ac.shard.checks.impl.ai.PersistentBufferService
import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.mitigation.MitigationScoreStore
import ac.shard.punishment.PunishmentManager
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import com.github.retrooper.packetevents.protocol.player.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.util.UUID
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerDataManagerTest {

  @Test
  fun `getPlayer keeps tracked player after shard disable is granted mid-session`() {
    val fixture = createFixture()
    val trackedPlayer = mockk<ShardPlayer>(relaxed = true)
    every { trackedPlayer.player } returns fixture.player

    trackedPlayers(fixture.manager)[fixture.player.uniqueId] = trackedPlayer

    assertNotNull(fixture.manager.getPlayer(fixture.player))

    fixture.disablePermission = true

    assertSame(trackedPlayer, fixture.manager.getPlayer(fixture.player))
    assertSame(trackedPlayer, fixture.manager.getPlayer(fixture.player.uniqueId))
    assertTrue(fixture.manager.getPlayers().contains(trackedPlayer))
    verify(exactly = 0) { fixture.alertManager.handlePlayerQuit(any()) }
  }

  private fun createFixture(): Fixture {
    val plugin = mockk<Shard>(relaxed = true)
    val server = mockk<Server>(relaxed = true)
    val pluginManager = mockk<PluginManager>(relaxed = true)
    every { plugin.server } returns server
    every { server.pluginManager } returns pluginManager
    every { pluginManager.registerEvents(any(), plugin) } just runs

    val scheduler = mockk<SchedulerService>(relaxed = true)
    every { scheduler.runSync(any<Player>(), any<Runnable>()) } answers
      {
        secondArg<Runnable>().run()
        mockk(relaxed = true)
      }

    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.aiSequence } returns 40
    every { configManager.cancelDuplicatePacket } returns true
    every { configManager.forceCancelDuplicatePacket } returns false
    every { configManager.ignoreDuplicatePacketRotation } returns true

    val alertManager = mockk<AlertManager>(relaxed = true)
    every { alertManager.handlePlayerQuit(any()) } just runs

    val dataCollectorManager = mockk<DataCollectorManager>(relaxed = true)
    every { dataCollectorManager.getSession(any()) } returns null
    every { dataCollectorManager.stopCollecting(any()) } returns false

    val checkManagerFactory = mockk<CheckManager.Factory>()
    every { checkManagerFactory.create(any()) } returns mockk(relaxed = true)

    val punishmentManagerFactory = mockk<PunishmentManager.Factory>()
    every { punishmentManagerFactory.create(any()) } returns mockk(relaxed = true)

    var disablePermission = false
    val player = mockk<Player>(relaxed = true)
    every { player.uniqueId } returns UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    every { player.isOnline } returns true
    every { player.hasPermission(any<String>()) } answers
      {
        firstArg<String>() == ExemptManager.DISABLE_PERMISSION && disablePermission
      }

    val manager =
      PlayerDataManager(
        plugin = plugin,
        alertManager = alertManager,
        dataCollectorManager = dataCollectorManager,
        configManager = configManager,
        aiServerProvider = mockk<AIServerProvider>(relaxed = true),
        exemptManager = ExemptManager(),
        scheduler = scheduler,
        checkManagerFactory = checkManagerFactory,
        punishmentManagerFactory = punishmentManagerFactory,
        eventBus = mockk<ShardEventBus>(relaxed = true),
        databaseManager = mockk<DatabaseManager>(relaxed = true),
        persistentBufferService = mockk<PersistentBufferService>(relaxed = true),
        mitigationScoreStore = mockk<MitigationScoreStore>(relaxed = true),
      )

    return Fixture(
      manager = manager,
      user = mockk<User>(relaxed = true),
      player = player,
      alertManager = alertManager,
      checkManagerFactory = checkManagerFactory,
      disablePermissionAccessor = { disablePermission },
      disablePermissionMutator = { disablePermission = it },
    )
  }

  private data class Fixture(
    val manager: PlayerDataManager,
    val user: User,
    val player: Player,
    val alertManager: AlertManager,
    val checkManagerFactory: CheckManager.Factory,
    private val disablePermissionAccessor: () -> Boolean,
    private val disablePermissionMutator: (Boolean) -> Unit,
  ) {
    var disablePermission: Boolean
      get() = disablePermissionAccessor()
      set(value) = disablePermissionMutator(value)
  }

  private companion object {
    val playersField =
      PlayerDataManager::class.java.getDeclaredField("players").apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    fun trackedPlayers(manager: PlayerDataManager): MutableMap<UUID, ShardPlayer> {
      return playersField.get(manager) as MutableMap<UUID, ShardPlayer>
    }
  }
}
