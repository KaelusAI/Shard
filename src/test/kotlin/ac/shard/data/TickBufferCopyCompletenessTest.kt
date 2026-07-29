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
package ac.shard.data

import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test

class TickBufferCopyCompletenessTest {

  @Test
  fun `every schema column survives window extraction`() {
    val buffer = TickBuffer(CAPACITY)
    val slot = buffer.currentSlot()
    fillDistinct(slot)
    buffer.markAttack()
    buffer.advance()

    val window = buffer.extractWindow(preWindow = 0, postWindow = 1, ticksSinceAttack = 1)
    assertNotNull(window)
    assertEquals(1, window.size)
    val copy = window[0]

    // Guards the guard: a column the reflective fill cannot perturb would pass the check below
    // while still being uncopied.
    val defaults = TickData()
    val unperturbed = TickSchema.fields.filter { it.rawFloat(slot) == it.rawFloat(defaults) }
    assertEquals(emptyList(), unperturbed.map { it.name }, "fillDistinct left columns at default")

    val dropped =
      TickSchema.fields.filter { it.name !in RESTAMPED && it.rawFloat(copy) != it.rawFloat(slot) }
    assertEquals(
      emptyList(),
      dropped.map { it.name },
      "TickBuffer.copyTickData drops schema columns; window consumers would see defaults",
    )
  }

  private fun fillDistinct(tick: TickData) {
    var i = 0
    for (field in TickData::class.java.declaredFields) {
      if (Modifier.isStatic(field.modifiers) || Modifier.isFinal(field.modifiers)) continue
      field.isAccessible = true
      i++
      when (field.type) {
        Boolean::class.javaPrimitiveType -> field.setBoolean(tick, true)
        Short::class.javaPrimitiveType -> field.setShort(tick, (i + 7).toShort())
        Int::class.javaPrimitiveType -> field.setInt(tick, i + 1000)
        Long::class.javaPrimitiveType -> field.setLong(tick, (i + 2000).toLong())
        Float::class.javaPrimitiveType -> field.setFloat(tick, i + 0.5f)
        Double::class.javaPrimitiveType -> field.setDouble(tick, i + 0.25)
      }
    }
    tick.movementBitfield = ALL_TRACKED_BITS
    tick.sequenceId = SEQUENCE_ID
  }

  private companion object {
    const val CAPACITY = 4
    const val SEQUENCE_ID = 4242
    const val ALL_TRACKED_BITS: Short = 0b11_1111_1111
    // extractWindow re-stamps the anchor offset on every copy by design.
    val RESTAMPED = setOf("ticks_to_attack")
  }
}
