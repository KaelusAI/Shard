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
package ac.shard.ai.stream

import ac.shard.http.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class ProfileParserTest {

  private val mapper = Json.mapper

  private val example =
    """
    {
      "profile_crc": "3fa91c02",
      "wire_columns": ["sequence_id", "tick_index", "yaw", "x", "attack_this_tick"],
      "chunk": 30, "max_chunk_rows": 130, "ring_ttl_s": 60,
      "primary": "b",
      "models": [
        {"id": "a", "title": "Model A", "short_title": "A", "schedule": "slide",
         "window": 10, "stride": 5, "events": ["hit_player"],
         "labels": ["x", "y"], "label_mode": "multilabel",
         "buffer": {"flag": 50, "reset": 25, "multiplier": 100, "decrease": 1, "tracked": 8},
         "role": {"alert": false, "mitigate": {"weight": 0.5}, "punish": false}},
        {"id": "b", "title": "Model B", "schedule": "attack", "pre": 20, "post": 20, "step": 20,
         "events": ["hit_player"], "buffer": {"flag": 50, "reset": 25, "multiplier": 100, "decrease": 1, "tracked": 8},
         "role": {"alert": {}, "mitigate": {"weight": 1.0}, "punish": {}}},
        {"id": "c", "title": "Model C", "short_title": "C", "schedule": "attack",
         "pre": 60, "post": 30, "step": 30, "events": ["hit_player", "hit_crystal"],
         "labels": ["x", "y"], "label_mode": "multilabel",
         "thresholds": {"x": {"cheat": 0.9, "legit": 0.1}},
         "buffer": {"flag": 50, "reset": 25, "multiplier": 100, "decrease": 1, "tracked": 8},
         "role": {"alert": {}, "mitigate": {"weight": 2.0}, "punish": {}}}
      ]
    }
    """
      .trimIndent()

  private fun node(): ObjectNode = mapper.readTree(example) as ObjectNode

  private fun ObjectNode.model(id: String): ObjectNode =
    (get("models") as ArrayNode).first { it.path("id").asString("") == id } as ObjectNode

  private fun parse(edit: ObjectNode.() -> Unit = {}) =
    ProfileParser.parse(node().apply(edit)).getOrThrow()

  private fun rejectReasons(edit: ObjectNode.() -> Unit): List<String> {
    val result = ProfileParser.parse(node().apply(edit))
    assertTrue(result.isFailure, "expected rejection")
    return assertIs<ProfileRejected>(result.exceptionOrNull()).reasons
  }

  private fun inertReason(id: String, edit: ObjectNode.() -> Unit): String {
    val profile = parse(edit)
    val model = profile.models.first { it.id == id }
    return assertNotNull(model.inert, "model $id should be inert")
  }

  @Test
  fun `accepts the example profile and derives the wire numbers`() {
    val profile = parse()
    assertEquals(0x3fa91c02L, profile.crc)
    assertEquals("3fa91c02", profile.configHeader)
    assertEquals(listOf("a", "b", "c"), profile.active.map { it.id })
    assertEquals("b", profile.primary.id)
    assertEquals(listOf("b", "c"), profile.punishModels.map { it.id })
    assertEquals(60, profile.wire.maxPre)
    assertEquals(setOf<Short>(0, 2), profile.anchorKinds)
    assertEquals(30, profile.closeHorizon)
    assertEquals(4 + 4 + 4 + 8 + 1, profile.wire.rowSize)
    assertEquals(130, profile.wire.maxChunkRows)
    assertEquals(60_000L, profile.ringTtlMs)
    val slide = profile.model("a")!!
    assertEquals(Schedule.SLIDE, slide.schedule)
    assertEquals(10, slide.span)
    assertEquals("A", slide.displayTitle)
    assertTrue(profile.model("b")!!.singleHead)
    assertEquals(50.0, profile.model("b")!!.buffer.flag)
    assertEquals(3.5, profile.mitigateWeightSum, 1e-9)
  }

  @Test
  fun `wire sections compare by columns and window numbers`() {
    val a = parse()
    assertEquals(a.wire, parse { put("profile_crc", "00000001") }.wire)
    assertFalse(a.wire == parse { put("chunk", 20) }.wire)
    assertFalse(a.wire == parse { put("max_chunk_rows", 200) }.wire)
  }

  @Test
  fun `rejects a malformed crc and bad wire columns`() {
    assertTrue(rejectReasons { put("profile_crc", "3fa91c0") }.any { "profile_crc" in it })
    fun cols(edit: ArrayNode.() -> Unit) = rejectReasons {
      (get("wire_columns") as ArrayNode).edit()
    }
    assertTrue(cols { add("yaw") }.any { "twice" in it })
    assertTrue(cols { add("no_such_column") }.any { "unknown" in it })
    assertTrue(cols { add("ticks_to_attack") }.any { "derived" in it })
    assertTrue(cols { remove(4) }.any { "attack_this_tick" in it })
    assertTrue(rejectReasons { putArray("wire_columns") }.any { "non-empty" in it })
  }

  @Test
  fun `rejects window numbers that do not fit`() {
    assertTrue(rejectReasons { put("chunk", 0) }.any { "chunk" in it })
    assertTrue(rejectReasons { put("max_chunk_rows", 80) }.any { "longest pre" in it })
    assertTrue(rejectReasons { put("max_chunk_rows", 600) }.any { "512" in it })
  }

  @Test
  fun `a model the plugin cannot serve goes inert and keeps its position`() {
    val profile = parse { model("a").put("schedule", "periodic") }
    assertEquals(listOf("a", "b", "c"), profile.models.map { it.id })
    assertEquals(listOf("b", "c"), profile.active.map { it.id })
    assertNull(profile.servable(0))
    assertEquals("b", profile.servable(1)?.id)
    assertEquals(-1, profile.indexOf("a"))
    assertEquals(2, profile.indexOf("c"))
    assertTrue(ProfileParser.warnings(profile).single().contains("model a"))
  }

  @Test
  fun `the inert model no longer shapes the wire`() {
    assertEquals(20, parse { model("c").put("schedule", "periodic") }.wire.maxPre)
  }

  @Test
  fun `model level problems make that model inert`() {
    assertTrue("at least 1" in inertReason("c") { model("c").put("step", 0) })
    assertTrue("window and stride" in inertReason("a") { model("a").put("stride", 0) })
    assertTrue("events" in inertReason("c") { model("c").putArray("events").add("teleport") })
    assertTrue("reserved" in inertReason("rotate") { model("c").put("id", "rotate") })
    assertTrue("twice" in parse { model("c").put("id", "a") }.models[2].inert!!)
    assertTrue(
      "weight" in
        inertReason("a") { model("a").withObject("role").putObject("mitigate").put("weight", -1.0) }
    )
    assertTrue(
      "buffer" in
        inertReason("c") { model("c").putObject("buffer").put("flag", 50).put("reset", 60) }
    )
  }

  @Test
  fun `label modes use the wire spellings only`() {
    assertTrue("multilabel" in inertReason("c") { model("c").put("label_mode", "multi_label") })
    assertTrue("multilabel" in inertReason("c") { model("c").remove("label_mode") })
    assertTrue(
      "needs labels" in
        inertReason("b") {
          put("primary", "c")
          model("b").put("label_mode", "multiclass")
        }
    )
    assertNull(parse { model("b").put("label_mode", "single") }.models[1].inert)
  }

  @Test
  fun `the cheat threshold is always point nine`() {
    assertTrue(
      "must be 0.9" in
        inertReason("c") {
          model("c").putObject("thresholds").putObject("x").put("cheat", 0.8).put("legit", 0.1)
        }
    )
    assertTrue(
      "unknown label" in
        inertReason("a") {
          model("a").putObject("thresholds").putObject("z").put("cheat", 0.9).put("legit", 0.1)
        }
    )
  }

  @Test
  fun `an attack model may have no pre rows`() {
    val profile = parse { model("b").put("pre", 0) }
    assertNull(profile.models[1].inert)
    assertEquals(0, profile.models[1].preRows)
  }

  @Test
  fun `a primary that cannot be served rejects the profile`() {
    assertTrue(rejectReasons { put("primary", "nope") }.any { "names no model" in it })
    assertTrue(
      rejectReasons { model("b").put("schedule", "periodic") }.any { "primary model b" in it }
    )
  }

  @Test
  fun `zero mitigation weight over active models rejects`() {
    assertTrue(
      rejectReasons {
          for (id in listOf("a", "b", "c")) {
            model(id).withObject("role").putObject("mitigate").put("weight", 0.0)
          }
        }
        .any { "sum to zero" in it }
    )
  }

  @Test
  fun `local narrowing turns models off but never the primary`() {
    val profile = parse()
    assertSame(profile, profile.narrowed(emptySet()))
    val narrowed = profile.narrowed(setOf("a", "b"))
    assertEquals(listOf("b", "c"), narrowed.active.map { it.id })
    assertEquals(StreamProfile.LOCALLY_OFF, narrowed.models[0].inert)
    assertEquals(profile.crc, narrowed.crc)
    assertTrue(ProfileParser.warnings(narrowed).isEmpty())
  }

  @Test
  fun `windows start on the events the active models ask for`() {
    val profile = parse()
    assertEquals(0b101, profile.windowStartMask)
    assertEquals(0b1, profile.narrowed(setOf("c")).windowStartMask)
  }

  private fun ObjectNode.addContinuous(stride: Int, events: Boolean = false): ObjectNode {
    val m =
      (get("models") as ArrayNode)
        .addObject()
        .put("id", "p")
        .put("schedule", "continuous")
        .put("window", 16)
        .put("stride", stride)
    m.set("buffer", model("b").get("buffer").deepCopy())
    if (events) m.putArray("events").add("hit_player")
    return m
  }

  @Test
  fun `a continuous model is accepted with any stride and without events`() {
    val ok = parse { addContinuous(1) }
    assertNull(ok.models[3].inert)
    assertEquals(Schedule.CONTINUOUS, ok.models[3].schedule)
    assertEquals(setOf<Short>(0, 2), ok.anchorKinds, "a continuous model adds no events")
    assertTrue("at least 1" in inertReason("p") { addContinuous(0) })
    assertTrue("must not list events" in inertReason("p") { addContinuous(64, events = true) })
  }

  @Test
  fun `empty events never mean always on`() {
    assertTrue("at least one" in inertReason("c") { model("c").putArray("events") })
    assertTrue("at least one" in inertReason("c") { model("c").remove("events") })
  }

  @Test
  fun `the primary cannot be continuous`() {
    assertTrue(
      rejectReasons {
          addContinuous(64)
          put("primary", "p")
        }
        .any { "start its windows on events" in it }
    )
  }

  @Test
  fun `a model id may run to 64 characters`() {
    val long = "m".repeat(64)
    val ok = parse { addContinuous(8).put("id", long) }
    assertNull(ok.models[3].inert)
    assertEquals(long, ok.models[3].id)
    assertTrue(
      "{1,64}" in inertReason("m".repeat(65)) { addContinuous(8).put("id", "m".repeat(65)) }
    )
  }

  @Test
  fun `roles carry their own buffer and labels`() {
    val profile = parse {
      model("c").withObject("role").putObject("alert").put("buffer", 30).putArray("labels").add("y")
    }
    val alert = profile.model("c")!!.role.alert!!
    assertEquals(30.0, alert.buffer)
    assertEquals(setOf("y"), alert.labels)
    assertTrue(alert.covers("y"))
    assertFalse(alert.covers("x"))
    assertNull(profile.model("a")!!.role.alert)
    assertEquals(0.5, profile.model("a")!!.mitigationWeight)
  }

  @Test
  fun `a role that does not fit its model makes the model inert`() {
    fun ObjectNode.role(id: String): ObjectNode = model(id).withObject("role")
    assertTrue("false or an object" in inertReason("c") { role("c").put("alert", true) })
    assertTrue(
      "below the flag" in inertReason("c") { role("c").putObject("alert").put("buffer", 50) }
    )
    assertTrue(
      "labels of this model" in
        inertReason("c") { role("c").putObject("punish").putArray("labels").add("z") }
    )
    assertTrue(
      rejectReasons { role("b").putObject("punish").putArray("labels").add("x") }
        .any { "needs a model with labels" in it }
    )
  }

  @Test
  fun `the buffer is required and may be tuned per label`() {
    assertTrue("buffer block is required" in inertReason("c") { model("c").remove("buffer") })
    val profile = parse {
      model("c").withObject("buffer").putObject("labels").putObject("x").put("flag", 60)
    }
    val buffer = profile.model("c")!!.buffer
    assertEquals(60.0, buffer.forLabel("x").flag)
    assertEquals(25.0, buffer.forLabel("x").resetOnFlag)
    assertEquals(50.0, buffer.forLabel("y").flag)
    assertTrue(
      "unknown label" in
        inertReason("c") { model("c").withObject("buffer").putObject("labels").putObject("z") }
    )
  }

  @Test
  fun `a model may feed another model's buffer, but only one step deep`() {
    val profile = parse { model("a").withObject("buffer").put("shared", "b").put("weight", 0.5) }
    assertEquals("b", profile.model("a")!!.buffer.shared)
    assertEquals(0.5, profile.model("a")!!.buffer.weight)
    assertEquals(1.0, profile.model("c")!!.buffer.weight)

    assertTrue(
      rejectReasons { model("a").withObject("buffer").put("shared", "a") }
        .any { "with itself" in it }
    )
    assertTrue(
      rejectReasons { model("a").withObject("buffer").put("shared", "z") }
        .any { "unknown model 'z'" in it }
    )
    assertTrue(
      rejectReasons {
          model("a").withObject("buffer").put("shared", "c")
          model("c").withObject("buffer").put("shared", "b")
        }
        .any { "shares its own with another model" in it }
    )
    assertTrue("weight" in inertReason("a") { model("a").withObject("buffer").put("weight", 0) })
  }
}
