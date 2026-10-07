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

import ac.shard.alert.AlertManager
import ac.shard.data.CollectManager
import ac.shard.detection.AiSnapshotStore
import ac.shard.detection.PersistentBufferService
import ac.shard.mitigation.MitigationLogStore
import ac.shard.mitigation.MitigationScoreStore
import ac.shard.scheduler.SchedulerService
import com.github.retrooper.packetevents.protocol.player.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.util.UUID
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerDataManagerTest {

  @Test
  fun `getPlayer keeps tracked player after shard disable is granted mid-session`() {
    val fixture = createFixture()
    val user = mockk<User>(relaxed = true)
    val trackedPlayer = mockk<ShardPlayer>(relaxed = true)
    every { trackedPlayer.player } returns fixture.player
    every { trackedPlayer.isAttached } returns true
    every { trackedPlayer.user } returns user
    every { trackedPlayer.uuid } returns fixture.player.uniqueId

    trackedPlayers(fixture.manager)[user] = trackedPlayer

    assertNotNull(fixture.manager.getPlayer(fixture.player))

    fixture.disablePermission = true

    assertSame(trackedPlayer, fixture.manager.getPlayer(fixture.player))
    assertSame(trackedPlayer, fixture.manager.getPlayer(fixture.player.uniqueId))
    assertTrue(fixture.manager.getPlayers().contains(trackedPlayer))
    verify(exactly = 0) { fixture.alertManager.handlePlayerQuit(any()) }
  }

  @Test
  fun `disconnect writes the mitigation to the log`() {
    val fixture = createFixture()
    val tracked = mockk<ShardPlayer>(relaxed = true)
    every { tracked.player } returns fixture.player
    every { tracked.user } returns fixture.user
    every { tracked.uuid } returns fixture.player.uniqueId
    every { tracked.isAttached } returns true
    every { tracked.persisted } returns java.util.concurrent.atomic.AtomicBoolean(false)
    every { fixture.user.uuid } returns fixture.player.uniqueId
    trackedPlayers(fixture.manager)[fixture.user] = tracked

    fixture.manager.handleUserDisconnect(fixture.user)

    verify(exactly = 1) { fixture.mitigationLogStore.saveOnQuit(tracked) }

    fixture.manager.saveAllBuffersSync()

    verify(exactly = 1) { fixture.mitigationLogStore.saveOnQuit(tracked) }
  }

  private fun createFixture(): Fixture {
    val scheduler = mockk<SchedulerService>(relaxed = true)
    every { scheduler.runSync(any<Player>(), any<Runnable>()) } answers
      {
        secondArg<Runnable>().run()
        mockk(relaxed = true)
      }
    every { scheduler.runAsync(any<Runnable>()) } answers
      {
        firstArg<Runnable>().run()
        mockk(relaxed = true)
      }

    val mitigationLogStore = mockk<MitigationLogStore>(relaxed = true)
    val alertManager = mockk<AlertManager>(relaxed = true)
    every { alertManager.handlePlayerQuit(any()) } just runs

    val collectManager = mockk<CollectManager>(relaxed = true)
    every { collectManager.getSession(any()) } returns null
    every { collectManager.stopCollecting(any()) } returns false

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
        playerFactory = mockk<ShardPlayerFactory>(relaxed = true),
        scheduler = scheduler,
        persistence =
          PlayerPersistence(
            mockk<PersistentBufferService>(relaxed = true),
            mockk<MitigationScoreStore>(relaxed = true),
            mitigationLogStore,
            mockk<AiSnapshotStore>(relaxed = true),
            mockk<PlayerDirectory>(relaxed = true),
            scheduler,
          ),
        alertManager = alertManager,
        collectManager = collectManager,
        sessions = mockk(relaxed = true),
      )

    return Fixture(
      manager = manager,
      user = mockk<User>(relaxed = true),
      player = player,
      alertManager = alertManager,
      mitigationLogStore = mitigationLogStore,
      disablePermissionAccessor = { disablePermission },
      disablePermissionMutator = { disablePermission = it },
    )
  }

  private data class Fixture(
    val manager: PlayerDataManager,
    val user: User,
    val player: Player,
    val alertManager: AlertManager,
    val mitigationLogStore: MitigationLogStore,
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
    fun trackedPlayers(manager: PlayerDataManager): MutableMap<User, ShardPlayer> {
      return playersField.get(manager) as MutableMap<User, ShardPlayer>
    }
  }
}
