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
package ac.shard.mitigation

import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.entity.Skeleton
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.projectiles.ProjectileSource

class MitigationChannelListenerTest {

  private val shooterId: UUID = UUID.randomUUID()
  private val arrowId: UUID = UUID.randomUUID()

  private class Fixture(
    val listener: MitigationChannelListener,
    val stamps: HitStamps,
    val shooter: Player,
  )

  private fun fixture(projectileMultiplier: Double): Fixture {
    val state = MitigationState()
    state.activeEffects = mapOf(MitigationSettings.PROJECTILE to projectileMultiplier)
    val shooter = mockk<Player>(relaxed = true) { every { uniqueId } returns shooterId }
    val shardPlayer =
      mockk<ShardPlayer>(relaxed = true) {
        every { mitigation } returns state
        every { uuid } returns shooterId
      }
    val playerDataManager =
      mockk<PlayerDataManager>(relaxed = true) { every { getPlayer(shooter) } returns shardPlayer }
    val stamps = HitStamps()
    return Fixture(MitigationChannelListener(playerDataManager, stamps), stamps, shooter)
  }

  private fun arrowFrom(source: ProjectileSource?): Arrow =
    mockk<Arrow>(relaxed = true) {
      every { uniqueId } returns arrowId
      every { shooter } returns source
    }

  @Test
  fun `an arrow shot under mitigation carries the multiplier of its shooter`() {
    val fixture = fixture(projectileMultiplier = 0.35)

    fixture.listener.onLaunch(ProjectileLaunchEvent(arrowFrom(fixture.shooter)))

    val stamp = fixture.stamps.take(arrowId)
    assertEquals(shooterId, stamp?.owner)
    assertEquals(0.35, stamp?.multiplier)
  }

  @Test
  fun `an arrow shot by a mob is left alone`() {
    val fixture = fixture(projectileMultiplier = 0.35)

    fixture.listener.onLaunch(ProjectileLaunchEvent(arrowFrom(mockk<Skeleton>(relaxed = true))))

    assertNull(fixture.stamps.take(arrowId))
  }

  @Test
  fun `an arrow shot without mitigation is not stamped`() {
    val fixture = fixture(projectileMultiplier = 1.0)

    fixture.listener.onLaunch(ProjectileLaunchEvent(arrowFrom(fixture.shooter)))

    assertNull(fixture.stamps.take(arrowId))
  }
}
