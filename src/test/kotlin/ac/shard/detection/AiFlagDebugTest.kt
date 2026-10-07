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
package ac.shard.detection

import ac.shard.ai.label.LabelKey
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class AiFlagDebugTest {
  @Test
  fun `a second model's unlabelled buffer is shown under the model's name`() {
    val text =
      buildAiFlagDebug(
        0.91,
        crossed = mapOf(LabelKey.UNATTRIBUTED to 50.3),
        tracked = mapOf("b/${LabelKey.UNATTRIBUTED}" to 49.7, "x" to 12.0),
        primary = "a",
        model = "b",
      )

    assertEquals("prob(b)=0.91 buffer a=50.3 b=49.7 x=12.0", text)
  }

  @Test
  fun `the summary leads with the flagging model and lists the rest with their last values`() {
    val cards =
      listOf(
        ModelCard("A", 0.0, 50.3, emptyList(), primary = true, probability = 0.12, id = "a"),
        ModelCard("B", 0.0, 49.7, emptyList(), probability = 0.4, id = "b"),
      )

    assertEquals("B 91% ◆ 49.7\nA 12% ◆ 50.3", buildAiFlagSummary("b", 0.91, cards))
  }

  @Test
  fun `without a profile the line keeps the plain buffer field`() {
    assertEquals(
      "prob=0.50 buffer=7.0",
      buildAiFlagDebug(0.5, crossed = mapOf(LabelKey.UNATTRIBUTED to 7.0)),
    )
  }
}
