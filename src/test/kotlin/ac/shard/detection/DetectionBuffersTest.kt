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

import ac.shard.ai.label.DetectionKey
import ac.shard.ai.label.LabelMode
import ac.shard.ai.label.LabelThresholds
import ac.shard.ai.label.LabelledVerdict
import ac.shard.ai.stream.BufferSpec
import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.Schedule
import ac.shard.ai.stream.StreamProfile
import ac.shard.ai.stream.TEST_BUFFER
import ac.shard.ai.stream.roles
import ac.shard.config.LocalAiSettings
import ac.shard.config.ModelOverride
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DetectionBuffersTest {

  private fun model(
    id: String,
    labels: List<String>,
    buffer: BufferSpec = TEST_BUFFER,
    punish: Boolean = true,
    thresholds: Map<String, LabelThresholds> = emptyMap(),
  ) =
    ModelSpec(
      id,
      id,
      null,
      Schedule.ATTACK,
      20,
      20,
      20,
      0,
      0,
      setOf(0),
      labels,
      if (labels.isEmpty()) LabelMode.SINGLE else LabelMode.MULTI_LABEL,
      emptySet(),
      emptyMap(),
      thresholds,
      buffer,
      1.0,
      roles(punish = punish),
    )

  private val ownBuffer = BufferSpec(40.0, 10.0, 50.0, 2.0, 2)
  private val main = model("b", emptyList())
  private val slide = model("a", listOf("x", "y"), ownBuffer, punish = false)
  private val deep =
    model("c", listOf("x", "y"), thresholds = mapOf("x" to LabelThresholds(0.8, 0.2)))
  private val profile =
    StreamProfile(1, emptyList(), 5, 512, listOf(slide, main, deep), main, 60_000, "")
  private val local = LocalAiSettings(true, emptyMap())

  private fun settings(m: ModelSpec, l: LocalAiSettings = local) = EffectiveSettings.of(m, l)

  private fun verdict(vararg values: Pair<String, Double>) = LabelledVerdict(mapOf(*values), true)

  @Test
  fun `every model uses the buffer from its own profile entry`() {
    assertEquals(TEST_BUFFER, settings(main).buffer)
    assertEquals(ownBuffer, settings(slide).buffer)
    assertEquals(TEST_BUFFER, settings(deep).buffer)
    assertEquals(2, settings(slide).maxTracked)
    assertEquals(LabelThresholds(0.8, 0.2), settings(deep).thresholds("x"))
    assertEquals(EffectiveSettings.DEFAULT_THRESHOLDS, settings(deep).thresholds("y"))
    assertEquals(LabelMode.SINGLE, settings(main).labelMode)
  }

  @Test
  fun `local overrides only narrow roles`() {
    val narrowed = local.copy(models = mapOf("c" to ModelOverride(disabledRoles = setOf("punish"))))
    assertNull(settings(deep, narrowed).role.punish)
    assertNotNull(settings(deep, narrowed).role.alert)
    val widen = local.copy(models = mapOf("a" to ModelOverride(disabledRoles = emptySet())))
    assertNull(settings(slide, widen).role.punish)
  }

  @Test
  fun `buffers are keyed by model and label`() {
    val buffers = DetectionBuffers()
    buffers.feed(deep, verdict("x" to 0.95), settings(deep))
    buffers.feed(slide, verdict("x" to 0.95), settings(slide))
    val snap = buffers.snapshot()
    assertEquals(15.0, snap.getValue(DetectionKey("c", "x")), 1e-9)
    assertEquals(2.5, snap.getValue(DetectionKey("a", "x")), 1e-9)
  }

  @Test
  fun `a label with its own buffer flags on its own threshold`() {
    val tuned =
      model(
        "t",
        listOf("x", "y"),
        TEST_BUFFER.copy(
          multiplier = 300.0,
          labels =
            mapOf("x" to TEST_BUFFER.copy(flag = 60.0, resetOnFlag = 10.0, multiplier = 300.0)),
        ),
      )
    val buffers = DetectionBuffers()
    val s = EffectiveSettings.of(tuned, local)
    val first = buffers.feed(tuned, verdict("x" to 1.0, "y" to 1.0), s)
    assertEquals(emptySet(), first.crossed.keys)
    val second = buffers.feed(tuned, verdict("x" to 1.0, "y" to 1.0), s)
    assertEquals(setOf("y"), second.crossed.keys, "y flags at 50, x waits for 60")
    val third = buffers.feed(tuned, verdict("x" to 1.0, "y" to 1.0), s)
    assertTrue("x" in third.crossed)
    assertEquals(10.0, buffers.snapshot()[DetectionKey("t", "x")])
  }

  @Test
  fun `flag crosses and resets with the model settings`() {
    val buffers = DetectionBuffers()
    var result = FeedResult(emptyMap(), 0.0, 0.0)
    repeat(6) { result = buffers.feed(deep, verdict("x" to 0.9), settings(deep)) }
    assertEquals(mapOf("x" to 60.0), result.crossed.mapValues { Math.round(it.value).toDouble() })
    assertEquals(25.0, buffers.snapshot().getValue(DetectionKey("c", "x")), 1e-9)
  }

  @Test
  fun `decay of absent labels stays inside the verdict model`() {
    val buffers = DetectionBuffers()
    buffers.feed(deep, verdict("x" to 0.95, "y" to 0.95), settings(deep))
    buffers.feed(main, verdict("_unattributed" to 0.99), settings(main))
    repeat(3) { buffers.feed(slide, verdict("x" to 0.5), settings(slide)) }
    val snap = buffers.snapshot()
    assertEquals(15.0, snap.getValue(DetectionKey("c", "x")), 1e-9)
    assertEquals(5.0, snap.getValue(DetectionKey("c", "y")), 1e-9)
    assertEquals(9.0, snap.getValue(DetectionKey("b", "_unattributed")), 1e-9)
    buffers.feed(deep, verdict("x" to 0.5), settings(deep))
    assertEquals(4.0, buffers.snapshot().getValue(DetectionKey("c", "y")), 1e-9)
  }

  @Test
  fun `eviction is bounded per model`() {
    val buffers = DetectionBuffers()
    val spare = model("a", listOf("x", "y", "z"), ownBuffer, punish = false)
    buffers.feed(
      spare,
      LabelledVerdict(mapOf("x" to 0.95, "y" to 0.91), false),
      settings(spare),
    )
    buffers.feed(deep, verdict("x" to 0.99), settings(deep))
    buffers.feed(spare, LabelledVerdict(mapOf("z" to 0.99), false), settings(spare))
    val keys = buffers.snapshot().keys
    assertEquals(
      setOf(
        DetectionKey("a", "x"),
        DetectionKey("a", "z"),
        DetectionKey("c", "x"),
      ),
      keys,
    )
  }

  @Test
  fun `punish max ignores models without the punish role`() {
    val buffers = DetectionBuffers()
    buffers.feed(slide, verdict("x" to 0.99), settings(slide))
    buffers.feed(deep, verdict("x" to 0.95), settings(deep))
    assertEquals(15.0, buffers.punishMax(profile), 1e-9)
    assertEquals(4.5, buffers.modelMax("a"), 1e-9)
  }

  @Test
  fun `clear restore and merge`() {
    val buffers = DetectionBuffers()
    buffers.restore(
      listOf(DetectionKey("c", "x") to 12.0, DetectionKey("b", "_unattributed") to 3.0)
    )
    buffers.mergeFrom(mapOf(DetectionKey("c", "x") to 5.0))
    assertEquals(12.0, buffers.snapshot().getValue(DetectionKey("c", "x")), 1e-9)
    val removed = buffers.clear { it.model == "c" }
    assertEquals(mapOf(DetectionKey("c", "x") to 12.0), removed)
    assertEquals(setOf(DetectionKey("b", "_unattributed")), buffers.snapshot().keys)
  }

  @Test
  fun `buffers restored before the profile move to its primary and orphans go away`() {
    val buffers = DetectionBuffers()
    buffers.restore(
      listOf(
        DetectionKey("fallback", "x") to 30.0,
        DetectionKey("b", "x") to 10.0,
        DetectionKey("q", "x") to 45.0,
        DetectionKey("c", "y") to 20.0,
      )
    )

    buffers.reconcile(profile, { it != "c" }, "fallback")

    assertEquals(mapOf(DetectionKey("b", "x") to 30.0), buffers.snapshot())
  }

  @Test
  fun `a selector keeps the model and canonicalises the label`() {
    assertEquals("c/x_y", DetectionKey.selector(" C/X Y "))
    assertEquals("x_y", DetectionKey.selector("X Y"))
    assertEquals(null, DetectionKey.selector("bad-id/x"))
    assertEquals("${"m".repeat(64)}/x", DetectionKey.selector("${"m".repeat(64)}/x"))
    assertEquals(null, DetectionKey.selector("${"m".repeat(65)}/x"))
  }

  @Test
  fun `detection key address round trip`() {
    val key = DetectionKey("c", "x")
    assertEquals("c/x", key.address())
    assertEquals(key, DetectionKey.parseAddress("c/x"))
    assertEquals(null, DetectionKey.parseAddress("x"))
    assertEquals(null, DetectionKey.parseAddress("c/"))
  }
}
