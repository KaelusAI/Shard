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

import ac.shard.config.MitigationsFile
import ac.shard.player.ExemptManager
import ac.shard.player.ShardPlayer
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class MitigationSkipTest {
  private val state = MitigationState()
  private val player =
    mockk<ShardPlayer>(relaxed = true) {
      every { mitigation } returns state
      every { isBedrockExempt } returns false
      every { exemptManager } returns mockk<ExemptManager>(relaxed = true)
    }
  private val score = MitigationsFile.DEFAULT_SCORE.copy(minAnswers = 3)
  private val skip = MitigationSkip {
    MitigationSettings(
      enabled = true,
      logEnabled = false,
      score = score,
      skip = SkipSettings(bedrock = false, followAiRegions = false),
      rules = emptyList(),
    )
  }

  private fun answer(times: Int) =
    repeat(times) { state.record(0.0, 0.5, 1_775_000_000_000L, score) }

  @Test
  fun `a player with too few answers is skipped for lack of data`() {
    answer(2)

    assertEquals(SkipReason.TOO_FEW_ANSWERS, skip.skipReason(player))
  }

  @Test
  fun `a player with enough answers is not skipped`() {
    answer(3)

    assertNull(skip.skipReason(player))
  }
}
