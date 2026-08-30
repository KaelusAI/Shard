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
import java.util.concurrent.CompletionException
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

  @Test
  fun `every configuration correction in the batch root reaches every item`() {
    val body =
      """
      {"count":2,
       "results":[{"probability":0.62,"probabilities":[0.62,0.04]},
                  {"probability":0.11,"probabilities":[0.11,0.02]}],
       "labels":["aim","trigger"],
       "label_titles":{"aim":"Aim Assist","trigger":"Auto Clicker"},
       "legit_labels":["clean"],
       "label_mode":"multilabel",
       "model_title":"Shard 2",
       "label_thresholds":{"aim":{"cheat":0.9,"legit":0.1},
                           "trigger":{"cheat":0.85,"legit":0.15}}}
      """
        .trimIndent()

    sendTwo(body).forEach { response ->
      val parsed = parser.parse(response)
      assertEquals(listOf("aim", "trigger"), parsed.labels)
      assertEquals(mapOf("aim" to "Aim Assist", "trigger" to "Auto Clicker"), parsed.labelTitles)
      assertEquals(listOf("clean"), parsed.legitLabels)
      assertEquals("multilabel", parsed.labelMode)
      assertEquals("Shard 2", parsed.modelTitle)
      assertEquals(
        mapOf("cheat" to 0.85, "legit" to 0.15),
        parsed.labelThresholds?.get("trigger"),
        "a correction the batch drops is a correction the plugin never applies",
      )
    }
  }

  @Test
  fun `a batch that answers a different number of windows fails every item instead of guessing`() {
    val body = """{"count":1,"results":[{"probability":0.62}]}"""

    val scheduler = immediateScheduler()
    val transport = transport(body, scheduler)
    val first = transport.send(byteArrayOf(1))
    val second = transport.send(byteArrayOf(2))

    for (future in listOf(first, second)) {
      val error = assertFailsWith<CompletionException> { future.join() }
      assertTrue(
        error.cause?.message.orEmpty().contains("mismatched"),
        "results are matched to players by position, so a count mismatch must not be silent",
      )
    }
  }

  private fun sendTwo(responseBody: String): List<String> {
    val transport = transport(responseBody, immediateScheduler())
    val first = transport.send(byteArrayOf(1))
    val second = transport.send(byteArrayOf(2))
    return listOf(first.join(), second.join())
  }

  private fun immediateScheduler(): SchedulerService {
    val scheduler = mockk<SchedulerService>()
    every { scheduler.runAsync(any()) } answers
      {
        firstArg<Runnable>().run()
        mockk<TaskHandle>(relaxed = true)
      }
    return scheduler
  }

  private fun transport(responseBody: String, scheduler: SchedulerService): BatchingAiTransport {
    val batchTransport =
      object : AiBatchTransport {
        override fun sendBatch(items: List<ByteArray>): CompletableFuture<String> =
          CompletableFuture.completedFuture(responseBody)
      }
    return BatchingAiTransport(
      batchTransport,
      mockk<AiTransport>(),
      scheduler,
      Logger.getAnonymousLogger(),
      BatchingAiTransport.BatchConfig(maxBatchSize = 2, maxDelayMs = 50),
    )
  }
}
