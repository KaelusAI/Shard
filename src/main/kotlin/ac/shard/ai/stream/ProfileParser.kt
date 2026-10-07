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

import ac.shard.ai.label.LabelKey
import ac.shard.ai.label.LabelMode
import ac.shard.ai.label.LabelThresholds
import ac.shard.data.TickSchema
import kotlin.math.abs
import tools.jackson.databind.JsonNode

class ProfileRejected(val reasons: List<String>) :
  IllegalArgumentException("profile rejected: " + reasons.joinToString("; "))

@Suppress("TooManyFunctions")
object ProfileParser {
  private const val MAX_MODELS = 16
  private const val DEFAULT_RING_TTL_S = 60L
  private const val MILLIS_PER_SECOND = 1000L
  private const val CHEAT_THRESHOLD = 0.9
  private const val THRESHOLD_EPSILON = 1e-9

  private val ID = Regex("[a-z0-9_]{1,64}")
  private val CRC = Regex("[0-9a-fA-F]{8}")
  val RESERVED_IDS =
    setOf("all", "any", "auto", "off", "none", "rotate", "strongest", "top", "every", "both")
  private val DERIVED = setOf("ticks_to_attack", "window_start")
  private val LABEL_MODES = LabelMode.entries.associateBy { it.wire }
  private const val REQUIRED_COLUMN = "attack_this_tick"
  private const val HEX_RADIX = 16
  private val UNUSABLE_BUFFER = BufferSpec(1.0, 0.0, 1.0, 0.0, 1)

  fun parse(node: JsonNode, raw: String = node.toString()): Result<StreamProfile> {
    val errors = mutableListOf<String>()
    val profile = build(node, raw, errors)
    return if (errors.isEmpty() && profile != null) {
      Result.success(profile)
    } else {
      Result.failure(ProfileRejected(errors.ifEmpty { listOf("profile could not be built") }))
    }
  }

  fun warnings(profile: StreamProfile): List<String> =
    profile.models
      .filter { it.inert != null && it.inert != StreamProfile.LOCALLY_OFF }
      .map { "model ${it.id} is not served by this plugin: ${it.inert}" }

  @Suppress("ReturnCount")
  private fun build(node: JsonNode, raw: String, errors: MutableList<String>): StreamProfile? {
    val crcText = node.path("profile_crc").asString("")
    val crc = if (CRC.matches(crcText)) crcText.toLong(HEX_RADIX) else null
    if (crc == null) errors += "profile_crc must be 8 hex digits"

    val columns = columns(node.path("wire_columns"), errors)
    val models = models(node.path("models"), errors)
    if (models.isEmpty()) return null

    val primaryId = node.path("primary").asString("")
    val primary = models.firstOrNull { it.id == primaryId }
    when {
      primary == null -> errors += "primary '$primaryId' names no model"
      primary.inert != null ->
        errors += "primary model $primaryId cannot be served: ${primary.inert}"
      primary.schedule == Schedule.CONTINUOUS ->
        errors += "primary model $primaryId must start its windows on events"
    }
    val active = models.filter { it.inert == null }
    errors += sharedBufferProblems(models)
    val chunk = node.path("chunk").asInt(-1)
    val maxChunkRows = node.path("max_chunk_rows").asInt(-1)
    if (active.isNotEmpty()) window(active.maxOf { it.preRows }, chunk, maxChunkRows, errors)
    val mitigating = active.filter { it.role.mitigate != null }
    if (mitigating.isNotEmpty() && mitigating.sumOf { it.mitigationWeight } <= 0.0) {
      errors += "mitigation weights of mitigating models sum to zero"
    }
    if (errors.isNotEmpty() || primary == null || crc == null) return null
    return StreamProfile(
      crc = crc,
      columns = columns,
      chunk = chunk,
      maxChunkRows = maxChunkRows,
      models = models,
      primary = primary,
      ringTtlMs = node.path("ring_ttl_s").asLong(DEFAULT_RING_TTL_S) * MILLIS_PER_SECOND,
      raw = raw,
    )
  }

  private fun columns(node: JsonNode, errors: MutableList<String>): List<WireColumn> {
    if (!node.isArray || node.isEmpty) {
      errors += "wire_columns must be a non-empty array of names"
      return emptyList()
    }
    val result = mutableListOf<WireColumn>()
    val seen = HashSet<String>()
    for (entry in node) {
      val name = entry.takeIf { it.isTextual }?.asString("").orEmpty()
      val field = TickSchema.fieldsByName[name]
      when {
        !seen.add(name) -> errors += "wire column '$name' is listed twice"
        name in DERIVED -> errors += "wire column '$name' is derived by the server"
        field == null -> errors += "wire column '$name' is unknown to this plugin"
        else -> result += WireColumn(name, field.wireType, field)
      }
    }
    if (REQUIRED_COLUMN !in seen) errors += "wire_columns must contain $REQUIRED_COLUMN"
    return result
  }

  private fun models(node: JsonNode, errors: MutableList<String>): List<ModelSpec> {
    if (!node.isArray || node.size() !in 1..MAX_MODELS) {
      errors += "models must hold 1 to $MAX_MODELS entries"
      return emptyList()
    }
    val ids = HashSet<String>()
    return node.values().map { entry ->
      val id = entry.path("id").asString("")
      val problems = mutableListOf<String>()
      when {
        !ID.matches(id) -> problems += "id must match [a-z0-9_]{1,64}"
        id in RESERVED_IDS -> problems += "id is reserved"
        !ids.add(id) -> problems += "id is listed twice"
      }
      val spec = model(entry, id, problems)
      if (problems.isEmpty()) spec else spec.copy(inert = problems.joinToString("; "))
    }
  }

  @Suppress("LongMethod", "CyclomaticComplexMethod")
  private fun model(node: JsonNode, id: String, problems: MutableList<String>): ModelSpec {
    val schedule =
      when (node.path("schedule").asString("")) {
        "attack" -> Schedule.ATTACK
        "slide" -> Schedule.SLIDE
        "continuous" -> Schedule.CONTINUOUS
        else -> {
          problems += "schedule must be attack, slide or continuous"
          Schedule.ATTACK
        }
      }
    val pre = node.path("pre").asInt(0)
    val post = node.path("post").asInt(0)
    val step = node.path("step").asInt(0)
    val window = node.path("window").asInt(0)
    val stride = node.path("stride").asInt(0)
    if (schedule == Schedule.ATTACK) {
      if (pre < 0 || post < 1 || step < 1)
        problems += "pre must not be negative, post and step must be at least 1"
    } else if (window < 1 || stride < 1) {
      problems += "window and stride must be at least 1"
    }

    val kinds =
      if (schedule == Schedule.CONTINUOUS) {
        if (node.has("events")) problems += "a continuous model must not list events"
        emptySet()
      } else {
        kinds(node.path("events"), problems)
      }
    val rawLabels =
      node.path("labels").takeIf { it.isArray }?.values()?.map { it.asString("") }.orEmpty()
    val labels = labels(rawLabels, problems)
    val mode = labelMode(node.path("label_mode"), labels, problems)
    val legit =
      node
        .path("legit_labels")
        .takeIf { it.isArray }
        ?.mapNotNull { LabelKey.canonical(it.asString("")) }
        .orEmpty()
    if (!labels.containsAll(legit)) problems += "legit_labels must be a subset of labels"
    val thresholds = thresholds(node.path("thresholds"), labels, problems)
    val titles =
      node
        .path("label_titles")
        .takeIf { it.isObject }
        ?.properties()
        ?.mapNotNull { (k, v) ->
          val key = LabelKey.canonical(k) ?: return@mapNotNull null
          LabelKey.title(v.asString(""))?.let { key to it }
        }
        ?.toMap()
        .orEmpty()

    val buffer = buffer(node.path("buffer"), labels, problems)
    val roleNode = node.path("role")
    val role =
      ModelRole(
        alert = role(roleNode, "alert", buffer, labels, problems),
        mitigate = role(roleNode, "mitigate", buffer, labels, problems),
        punish = role(roleNode, "punish", buffer, labels, problems),
      )
    val weight = roleNode.path("mitigate").path("weight").asDouble(1.0)
    if (!weight.isFinite() || weight < 0.0)
      problems += "role.mitigate.weight must be finite and non-negative"
    val title = LabelKey.title(node.path("title").asString("")) ?: id

    return ModelSpec(
      id = id,
      title = title,
      shortTitle = node.path("short_title").asString(null)?.let(LabelKey::title),
      schedule = schedule,
      pre = pre,
      post = post,
      step = step,
      window = window,
      stride = stride,
      anchorKinds = kinds,
      labels = labels,
      labelMode = mode,
      legitLabels = legit.toSet(),
      labelTitles = titles,
      thresholds = thresholds,
      buffer = buffer ?: UNUSABLE_BUFFER,
      mitigationWeight = weight,
      role = role,
    )
  }

  private fun kinds(node: JsonNode, problems: MutableList<String>): Set<Short> {
    val names = if (node.isArray) node.values().map { it.asString("") } else emptyList()
    val unknown = names.filter { WindowEvents.kind(it) == null }
    when {
      names.isEmpty() -> problems += "events must name at least one window start event"
      unknown.isNotEmpty() -> problems += "events $unknown are unknown to this plugin"
    }
    return names.mapNotNull(WindowEvents::kind).toSet()
  }

  private fun labelMode(
    node: JsonNode,
    labels: List<String>,
    problems: MutableList<String>,
  ): LabelMode {
    val mode = node.takeIf { it.isTextual }?.let { LABEL_MODES[it.asString("")] }
    return when {
      labels.isEmpty() && (mode == null || mode == LabelMode.SINGLE) -> LabelMode.SINGLE
      labels.isEmpty() -> {
        problems += "label_mode ${mode!!.wire} needs labels"
        mode
      }
      mode == null || mode == LabelMode.SINGLE -> {
        problems += "label_mode must be multilabel or multiclass for a model with labels"
        LabelMode.SINGLE
      }
      else -> mode
    }
  }

  private fun labels(raw: List<String>, problems: MutableList<String>): List<String> {
    if (raw.any { '/' in it }) problems += "labels must not contain '/'"
    val canonical = raw.map { LabelKey.canonical(it) }
    if (canonical.any { it == null } || canonical.toSet().size != canonical.size) {
      problems += "labels collide or vanish after canonicalisation"
    }
    return canonical.filterNotNull().distinct()
  }

  private fun thresholds(
    node: JsonNode,
    labels: List<String>,
    problems: MutableList<String>,
  ): Map<String, LabelThresholds> {
    if (!node.isObject) return emptyMap()
    val result = LinkedHashMap<String, LabelThresholds>()
    for ((name, value) in node.properties()) {
      val key = LabelKey.canonical(name)
      val parsed =
        LabelThresholds.parse(
          value.path("cheat").takeIf { it.isNumber }?.asDouble(0.0),
          value.path("legit").takeIf { it.isNumber }?.asDouble(0.0),
        )
      when {
        key == null || key !in labels -> problems += "threshold for unknown label '$name'"
        parsed == null -> problems += "threshold for '$name' is out of range"
        abs(parsed.cheat - CHEAT_THRESHOLD) > THRESHOLD_EPSILON ->
          problems += "cheat threshold for '$name' must be $CHEAT_THRESHOLD"
        else -> result[key] = parsed
      }
    }
    return result
  }

  private fun buffer(
    node: JsonNode,
    labels: List<String>,
    problems: MutableList<String>,
  ): BufferSpec? {
    val base =
      BufferSpec(
          flag = node.path("flag").asDouble(-1.0),
          resetOnFlag = node.path("reset").asDouble(-1.0),
          multiplier = node.path("multiplier").asDouble(-1.0),
          decrease = node.path("decrease").asDouble(-1.0),
          maxTracked = node.path("tracked").asInt(0),
        )
        .takeIf { node.isObject && usable(it) && it.maxTracked >= 1 }
    if (base == null) {
      problems += if (node.isObject) "buffer block is out of range" else "buffer block is required"
      return null
    }
    val shared = node.path("shared").takeIf { it.isTextual }?.asString("")
    val weight = node.path("weight").asDouble(1.0)
    if (shared != null && LabelKey.canonical(shared) != shared)
      problems += "buffer shared '$shared' is not a model id"
    if (!(weight > 0.0) || weight.isInfinite()) problems += "buffer weight must be above zero"
    return base.copy(
      labels = labelBuffers(node.path("labels"), base, labels, problems),
      shared = shared,
      weight = weight,
    )
  }

  private fun labelBuffers(
    node: JsonNode,
    base: BufferSpec,
    labels: List<String>,
    problems: MutableList<String>,
  ): Map<String, BufferSpec> {
    val overrides = LinkedHashMap<String, BufferSpec>()
    for ((name, value) in node.takeIf { it.isObject }?.properties().orEmpty()) {
      val key = LabelKey.canonical(name)
      val spec =
        base.copy(
          flag = value.path("flag").asDouble(base.flag),
          resetOnFlag = value.path("reset").asDouble(base.resetOnFlag),
          multiplier = value.path("multiplier").asDouble(base.multiplier),
          decrease = value.path("decrease").asDouble(base.decrease),
        )
      when {
        key == null || key !in labels -> problems += "buffer for unknown label '$name'"
        !usable(spec) -> problems += "buffer for '$name' is out of range"
        else -> overrides[key] = spec
      }
    }
    return overrides
  }

  private fun sharedBufferProblems(models: List<ModelSpec>): List<String> = models.mapNotNull { m ->
    val id = m.buffer.shared ?: return@mapNotNull null
    val target = models.firstOrNull { it.id == id }
    when {
      id == m.id -> "model ${m.id} cannot share its buffer with itself"
      target == null -> "model ${m.id} shares its buffer with unknown model '$id'"
      target.buffer.shared != null ->
        "model ${m.id} shares its buffer with ${target.id}, which shares its own with another model"
      else -> null
    }
  }

  private fun usable(b: BufferSpec): Boolean =
    b.flag > 0.0 &&
      b.resetOnFlag >= 0.0 &&
      b.resetOnFlag < b.flag &&
      b.multiplier > 0.0 &&
      b.decrease >= 0.0

  private fun role(
    node: JsonNode,
    name: String,
    buffer: BufferSpec?,
    labels: List<String>,
    problems: MutableList<String>,
  ): RoleSpec? {
    val entry = node.path(name)
    val off = entry.isMissingNode || entry.isNull || (entry.isBoolean && !entry.asBoolean(false))
    if (off || !entry.isObject) {
      if (!off) problems += "role.$name must be false or an object"
      return null
    }
    val at = entry.path("buffer").takeIf { it.isNumber }?.asDouble(0.0)
    val picked =
      entry
        .path("labels")
        .takeIf { it.isArray }
        ?.values()
        ?.map {
          LabelKey.canonical(it.asString(""))
        }
    val flag = buffer?.flag ?: Double.MAX_VALUE
    listOfNotNull(roleBufferProblem(entry, at, flag), roleLabelsProblem(picked, labels)).forEach {
      problems += "role.$name.$it"
    }
    return RoleSpec(at, picked?.filterNotNull()?.toSet())
  }

  private fun roleBufferProblem(entry: JsonNode, at: Double?, flag: Double): String? =
    when {
      entry.has("buffer") && at == null -> "buffer must be a number"
      at != null && (at < 0.0 || at >= flag) -> "buffer must be at least 0 and below the flag"
      else -> null
    }

  private fun roleLabelsProblem(picked: List<String?>?, labels: List<String>): String? =
    when {
      picked == null -> null
      labels.isEmpty() -> "labels needs a model with labels"
      picked.isEmpty() -> "labels must not be empty, leave it out for every label"
      !labels.containsAll(picked) -> "labels must be labels of this model"
      else -> null
    }

  private fun window(maxPre: Int, chunk: Int, maxChunkRows: Int, errors: MutableList<String>) {
    if (chunk < 1) errors += "chunk must be at least 1"
    if (maxChunkRows > WireSection.MAX_CHUNK_ROWS)
      errors += "max_chunk_rows must not exceed ${WireSection.MAX_CHUNK_ROWS}"
    if (maxPre + chunk > maxChunkRows)
      errors += "max_chunk_rows must hold the longest pre ($maxPre) plus one chunk ($chunk)"
  }
}
