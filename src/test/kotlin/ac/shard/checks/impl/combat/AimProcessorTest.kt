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
package ac.shard.checks.impl.combat

import ac.shard.player.ShardPlayer
import ac.shard.utils.data.HeadRotation
import ac.shard.utils.update.RotationUpdate
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AimProcessorTest {

  private fun newProcessor(): AimProcessor = AimProcessor(mockk<ShardPlayer>(relaxed = true))

  private fun AimProcessor.feed(deltaYaw: Float, deltaPitch: Float) {
    process(RotationUpdate(HeadRotation(), HeadRotation(), deltaYaw, deltaPitch))
  }

  private fun AimProcessor.warmUp(delta: Float = 1.0f, samples: Int = 20) {
    repeat(samples) { feed(delta, delta) }
  }

  private fun AimProcessor.feedAbsolute(fromYaw: Float, toYaw: Float, deltaPitch: Float = 1.0f) {
    process(
      RotationUpdate(
        HeadRotation(fromYaw, 0f),
        HeadRotation(toYaw, 0f),
        toYaw - fromYaw,
        deltaPitch,
      )
    )
  }

  @Test
  fun `consistent stream validates both axes with delta dots normalized to one`() {
    val aim = newProcessor()
    aim.warmUp()

    assertTrue(aim.modeYawValid && aim.modePitchValid)
    assertEquals(1.0, aim.modeX, 1e-9)
    assertEquals(1.0, aim.modeY, 1e-9)

    val dots = aim.consumeDots()
    assertEquals(1.0f, dots.yaw, 1e-6f)
    assertEquals(1.0f, dots.pitch, 1e-6f)
  }

  @Test
  fun `consumeDots yields zero on a tick without a fresh rotation`() {
    val aim = newProcessor()
    aim.warmUp()

    val fresh = aim.consumeDots()
    assertEquals(1.0f, fresh.yaw, 1e-6f)
    assertEquals(1.0f, fresh.pitch, 1e-6f)

    val stale = aim.consumeDots()
    assertEquals(0.0f, stale.yaw)
    assertEquals(0.0f, stale.pitch)

    aim.feed(1.0f, 1.0f)
    val again = aim.consumeDots()
    assertEquals(1.0f, again.yaw, 1e-6f)
    assertEquals(1.0f, again.pitch, 1e-6f)
  }

  @Test
  fun `reset clears mode window and dots`() {
    val aim = newProcessor()
    aim.warmUp()
    assertTrue(aim.modeYawValid && aim.modePitchValid)

    aim.reset()

    assertFalse(aim.modeYawValid && aim.modePitchValid)
    assertEquals(0.0, aim.modeX, 1e-9)
    assertEquals(0.0, aim.modeY, 1e-9)

    val dots = aim.consumeDots()
    assertEquals(0.0f, dots.yaw)
    assertEquals(0.0f, dots.pitch)
  }

  @Test
  fun `high absolute yaw still resolves the real step`() {
    val aim = newProcessor()
    var yaw = 40000f
    repeat(80) { i ->
      val next = yaw + (1 + i % 3) * 0.15f
      aim.feedAbsolute(yaw, next)
      yaw = next
    }

    assertTrue(aim.modeYawValid)
    assertEquals(0.1484375, aim.modeX, 1e-9)
    assertTrue(aim.modePitchValid)
  }

  @Test
  fun `mode refuses samples once float resolution outgrows the step`() {
    val aim = newProcessor()
    var yaw = 200000f
    repeat(120) {
      val next = yaw + 0.15f
      aim.feedAbsolute(yaw, next)
      yaw = next
    }

    assertFalse(aim.modeYawValid)
    assertEquals(0.0, aim.modeX, 1e-9)
    assertTrue(aim.modePitchValid)
  }

  @Test
  fun `absolute yaw below the degradation boundary samples exactly like a zero based stream`() {
    val anchored = newProcessor()
    var yaw = 5000f
    repeat(20) {
      val next = yaw + 1.0f
      anchored.feedAbsolute(yaw, next)
      yaw = next
    }

    val baseline = newProcessor()
    baseline.warmUp()

    assertTrue(anchored.modeYawValid)
    assertEquals(baseline.modeX, anchored.modeX, 1e-9)
    assertEquals(baseline.consumeDots().yaw, anchored.consumeDots().yaw, 1e-6f)
  }

  @Test
  fun `a learned mode survives samples that are too coarse to measure`() {
    val aim = newProcessor()
    var yaw = 16000f
    repeat(90) {
      val next = yaw + 4.0f
      aim.feedAbsolute(yaw, next)
      yaw = next
    }
    assertTrue(aim.modeYawValid)
    assertEquals(4.0, aim.modeX, 1e-9)

    yaw = 300000f
    repeat(90) {
      val next = yaw + 0.15f
      aim.feedAbsolute(yaw, next)
      yaw = next
    }

    assertTrue(aim.modeYawValid)
    assertEquals(4.0, aim.modeX, 1e-9)
  }

  @Test
  fun `mode reflects samples after reset just like a fresh processor`() {
    val reused = newProcessor()
    reused.warmUp(delta = 2.0f)
    reused.reset()
    reused.warmUp(delta = 1.0f)

    val fresh = newProcessor()
    fresh.warmUp(delta = 1.0f)

    assertEquals(fresh.modeX, reused.modeX, 1e-9)
    assertEquals(
      fresh.modeYawValid && fresh.modePitchValid,
      reused.modeYawValid && reused.modePitchValid,
    )
    assertEquals(fresh.consumeDots().yaw, reused.consumeDots().yaw, 1e-6f)
  }
}
