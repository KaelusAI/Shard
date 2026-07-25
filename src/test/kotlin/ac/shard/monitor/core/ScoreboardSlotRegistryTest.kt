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
package ac.shard.monitor.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreboardSlotRegistryTest {
  private val viewer = UUID.randomUUID()
  private val other = UUID.randomUUID()
  private val noop = SlotLostCallback { _, _, _ -> }

  @Test
  fun `a fresh registry is idle`() {
    assertTrue(ScoreboardSlotRegistry().isIdle())
  }

  @Test
  fun `claiming leaves the registry busy and readable`() {
    val registry = ScoreboardSlotRegistry()

    registry.claim(viewer, SlotClaim(2, "obj", noop))

    assertFalse(registry.isIdle())
    assertEquals(listOf(2), registry.claimsFor(viewer)?.map { it.slot })
    assertNull(registry.claimsFor(other))
  }

  @Test
  fun `a second claim on the same slot replaces the first`() {
    val registry = ScoreboardSlotRegistry()

    registry.claim(viewer, SlotClaim(2, "old", noop))
    registry.claim(viewer, SlotClaim(2, "new", noop))

    assertEquals(listOf("new"), registry.claimsFor(viewer)?.map { it.objective })
  }

  @Test
  fun `claims on different slots coexist`() {
    val registry = ScoreboardSlotRegistry()

    registry.claim(viewer, SlotClaim(1, "sidebar", noop))
    registry.claim(viewer, SlotClaim(2, "below", noop))

    assertEquals(setOf(1, 2), registry.claimsFor(viewer)?.map { it.slot }?.toSet())
  }

  @Test
  fun `releasing one slot keeps the others`() {
    val registry = ScoreboardSlotRegistry()
    registry.claim(viewer, SlotClaim(1, "sidebar", noop))
    registry.claim(viewer, SlotClaim(2, "below", noop))

    registry.release(viewer, 1)

    assertEquals(listOf(2), registry.claimsFor(viewer)?.map { it.slot })
    assertFalse(registry.isIdle())
  }

  @Test
  fun `releasing the last slot drops the viewer entirely`() {
    val registry = ScoreboardSlotRegistry()
    registry.claim(viewer, SlotClaim(2, "below", noop))

    registry.release(viewer, 2)

    assertNull(registry.claimsFor(viewer))
    assertTrue(registry.isIdle())
  }

  @Test
  fun `releaseAll drops one viewer without touching another`() {
    val registry = ScoreboardSlotRegistry()
    registry.claim(viewer, SlotClaim(2, "below", noop))
    registry.claim(other, SlotClaim(2, "below", noop))

    registry.releaseAll(viewer)

    assertNull(registry.claimsFor(viewer))
    assertEquals(listOf(2), registry.claimsFor(other)?.map { it.slot })
    assertFalse(registry.isIdle())
  }

  @Test
  fun `releasing an unknown viewer is harmless`() {
    val registry = ScoreboardSlotRegistry()

    registry.release(viewer, 2)
    registry.releaseAll(viewer)

    assertTrue(registry.isIdle())
  }
}
