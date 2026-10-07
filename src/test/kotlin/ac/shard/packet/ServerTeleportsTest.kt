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
package ac.shard.packet

import ac.shard.player.ShardPlayer
import ac.shard.player.state.MovementState
import ac.shard.player.state.TransactionTracker
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ServerTeleportsTest {
  private class Case(
    val received: Int,
    val entries: List<Pair<Int, Boolean>>,
    val relative: Boolean,
  )

  private val at = Location(1.0, 2.0, 3.0, 4f, 5f)

  private fun player(received: Int): ShardPlayer {
    val tracker = TransactionTracker().apply { lastTransactionReceived.set(received) }
    val movement = MovementState().apply { x = 0.5 }
    return mockk {
      every { transactions } returns tracker
      every { this@mockk.movement } returns movement
      every { pendingTeleports } returns ConcurrentLinkedQueue()
      every { pendingRotations } returns ConcurrentLinkedQueue()
      every { getMovementThreshold() } returns 0.03
    }
  }

  private fun flying(position: Boolean, rotation: Boolean) =
    mockk<WrapperPlayClientPlayerFlying> {
      every { hasPositionChanged() } returns position
      every { hasRotationChanged() } returns rotation
      every { location } returns at
    }

  private fun teleport(id: Int, lands: Boolean, relative: Boolean): ShardPlayer.TeleportData {
    val x = if (relative) at.x - 0.5 else at.x
    val position = Vector3d(if (lands) x else x + 1.0, at.y, at.z)
    val flags = if (relative) RelativeFlag.X else RelativeFlag.NONE
    return ShardPlayer.TeleportData(position, at.yaw, at.pitch, flags, id)
  }

  private fun rotation(id: Int, lands: Boolean) =
    ShardPlayer.RotationData(at.yaw, if (lands) at.pitch else at.pitch + 1f, false, false, id)

  private fun cases(): List<Case> {
    val entries = listOf(1 to true, 1 to false, 2 to true, 2 to false, 3 to true, 3 to false)
    val queues =
      listOf(emptyList<Pair<Int, Boolean>>()) +
        entries.map { listOf(it) } +
        entries.flatMap { a -> entries.filter { it.first >= a.first }.map { listOf(a, it) } } +
        entries.flatMap { a ->
          entries
            .filter { it.first >= a.first }
            .flatMap { b -> entries.filter { it.first >= b.first }.map { listOf(a, b, it) } }
        }
    return (0..4).flatMap { received ->
      queues.flatMap { q -> listOf(false, true).map { Case(received, q, it) } }
    }
  }

  @Test
  fun `the teleport queue behaves as before on every ordering`() {
    for (case in cases()) {
      for ((position, rotation) in listOf(true to true, true to false, false to true)) {
        val old = player(case.received)
        val new = player(case.received)
        case.entries.forEach { (id, lands) ->
          old.pendingTeleports.add(teleport(id, lands, case.relative))
          new.pendingTeleports.add(teleport(id, lands, case.relative))
        }
        val flying = flying(position, rotation)

        val expected = legacyTeleport(old, flying)
        val actual = ServerTeleports.acceptTeleport(new, flying)

        assertEquals(expected, actual)
        assertEquals(
          old.pendingTeleports.map { it.transactionId to it.location.x },
          new.pendingTeleports.map { it.transactionId to it.location.x },
        )
      }
    }
  }

  @Test
  fun `the rotation queue behaves as before on every ordering`() {
    for (case in cases().filter { !it.relative }) {
      for ((position, rotation) in listOf(false to true, true to true, false to false)) {
        val old = player(case.received)
        val new = player(case.received)
        case.entries.forEach { (id, lands) ->
          old.pendingRotations.add(rotation(id, lands))
          new.pendingRotations.add(rotation(id, lands))
        }
        val flying = flying(position, rotation)

        val expected = legacyRotation(old, flying)
        val actual = ServerTeleports.acceptRotation(new, flying)

        assertEquals(expected, actual)
        assertEquals(
          old.pendingRotations.map { it.transactionId to it.pitch },
          new.pendingRotations.map { it.transactionId to it.pitch },
        )
      }
    }
  }

  @Suppress("ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod")
  private fun legacyTeleport(player: ShardPlayer, flying: WrapperPlayClientPlayerFlying): Boolean {
    if (
      !flying.hasPositionChanged() ||
        !flying.hasRotationChanged() ||
        player.pendingTeleports.isEmpty()
    ) {
      return false
    }
    val movement = player.movement
    while (player.pendingTeleports.isNotEmpty()) {
      val teleport = player.pendingTeleports.peek() ?: break
      val lastTransaction = player.transactions.lastTransactionReceived.get()
      if (lastTransaction < teleport.transactionId) return false
      val loc = flying.location
      val flags = teleport.flags
      val ex =
        if (flags.has(RelativeFlag.X)) movement.x + teleport.location.x else teleport.location.x
      val ey =
        if (flags.has(RelativeFlag.Y)) movement.y + teleport.location.y else teleport.location.y
      val ez =
        if (flags.has(RelativeFlag.Z)) movement.z + teleport.location.z else teleport.location.z
      val threshold = if (teleport.isRelativePos()) player.getMovementThreshold() else 0.0
      val matches =
        abs(loc.x - ex) <= threshold &&
          abs(loc.y - ey) <= 1.0E-7 + threshold &&
          abs(loc.z - ez) <= threshold
      if (matches && teleport.rotationMatches(loc.yaw, loc.pitch)) {
        player.pendingTeleports.poll()
        return true
      }
      if (lastTransaction > teleport.transactionId) {
        player.pendingTeleports.poll()
        continue
      }
      return false
    }
    return false
  }

  @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
  private fun legacyRotation(player: ShardPlayer, flying: WrapperPlayClientPlayerFlying): Boolean {
    if (
      !flying.hasRotationChanged() ||
        flying.hasPositionChanged() ||
        player.pendingRotations.isEmpty()
    ) {
      return false
    }
    while (player.pendingRotations.isNotEmpty()) {
      val rotation = player.pendingRotations.peek() ?: break
      val lastTransaction = player.transactions.lastTransactionReceived.get()
      if (lastTransaction < rotation.transactionId) return false
      val yawMatches = rotation.relativeYaw || flying.location.yaw == rotation.yaw
      val pitchMatches = rotation.relativePitch || flying.location.pitch == rotation.pitch
      if (yawMatches && pitchMatches) {
        player.pendingRotations.poll()
        return true
      }
      if (lastTransaction > rotation.transactionId) {
        player.pendingRotations.poll()
        continue
      }
      return false
    }
    return false
  }
}
