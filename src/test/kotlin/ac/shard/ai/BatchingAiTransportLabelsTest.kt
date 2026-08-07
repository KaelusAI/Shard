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
package ac.shard.ai

import ac.shard.platform.scheduler.TaskHandle
import ac.shard.scheduler.SchedulerService
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class BatchingAiTransportLabelsTest {
  private val parser = JacksonAiResponseParser()

  @Test
  fun `lifts top-level labels into every item`() {
    val body =
      """
      {"count":2,
       "results":[{"probability":0.62,"probabilities":[0.62,0.04]},
                  {"probability":0.11,"probabilities":[0.11,0.02]}],
       "labels":["AIM_AURA","AUTOCRYSTAL"]}
      """
        .trimIndent()

    val responses = sendTwo(body)

    responses.forEach { response ->
      val parsed = parser.parse(response)
      assertEquals(listOf("AIM_AURA", "AUTOCRYSTAL"), parsed.labels)
    }
    assertEquals(listOf(0.62, 0.04), parser.parse(responses[0]).probabilities)
    assertEquals(listOf(0.11, 0.02), parser.parse(responses[1]).probabilities)
  }

  @Test
  fun `single-headed batch carries no labels`() {
    val body = """{"count":2,"results":[{"probability":0.62},{"probability":0.11}]}"""

    sendTwo(body).forEach { response ->
      val parsed = parser.parse(response)
      assertNull(parsed.labels)
      assertNull(parsed.probabilities)
    }
  }

  private fun sendTwo(responseBody: String): List<String> {
    val scheduler = mockk<SchedulerService>()
    every { scheduler.runAsync(any()) } answers
      {
        firstArg<Runnable>().run()
        mockk<TaskHandle>(relaxed = true)
      }

    val batchTransport =
      object : AiBatchTransport {
        override fun sendBatch(items: List<ByteArray>): CompletableFuture<String> =
          CompletableFuture.completedFuture(responseBody)
      }

    val transport =
      BatchingAiTransport(
        batchTransport,
        mockk<AiTransport>(),
        scheduler,
        Logger.getAnonymousLogger(),
        BatchingAiTransport.BatchConfig(maxBatchSize = 2, maxDelayMs = 50),
      )

    val first = transport.send(byteArrayOf(1))
    val second = transport.send(byteArrayOf(2))
    return listOf(first.join(), second.join())
  }
}
