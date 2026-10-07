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
package ac.shard.editor

import ac.shard.http.Json
import java.time.Instant
import tools.jackson.databind.JsonNode

data class EditorResult(
  val resultId: String,
  val sessionId: String,
  val issuedAt: Instant,
  val baseline: Map<String, String>,
  val delta: Delta,
)

sealed interface DecodeResult {
  data class Decoded(val result: EditorResult) : DecodeResult

  data class Malformed(val reason: String) : DecodeResult
}

private val MAPPER = Json.mapper
private const val MAX_CHANGES = 512

internal object DeltaCodec {

  fun decode(payload: String): DecodeResult {
    val root = runCatching { MAPPER.readTree(payload) }.getOrNull()
    return when {
      root == null || !root.isObject -> DecodeResult.Malformed("the payload is not an object")
      root.path("result_id").asString("").isBlank() -> DecodeResult.Malformed("no result_id")
      root.path("session_id").asString("").isBlank() -> DecodeResult.Malformed("no session_id")
      else -> withTime(root)
    }
  }

  private fun withTime(root: JsonNode): DecodeResult {
    val issued = runCatching { Instant.parse(root.path("issued_at").asString("")) }.getOrNull()
    return if (issued == null) {
      DecodeResult.Malformed("issued_at is not a time in UTC")
    } else {
      assemble(root, issued)
    }
  }

  private fun assemble(root: JsonNode, issued: Instant): DecodeResult {
    val changes = changes(root.path("changes"))
    val total = changes.values.sumOf { it.size }
    return if (total > MAX_CHANGES) {
      DecodeResult.Malformed("more than $MAX_CHANGES changes in one result")
    } else {
      DecodeResult.Decoded(
        EditorResult(
          resultId = root.path("result_id").asString(""),
          sessionId = root.path("session_id").asString(""),
          issuedAt = issued,
          baseline = strings(root.path("baseline")),
          delta =
            Delta(
              changes = changes,
              disabledRegions = regions(root.path("disabled_regions")),
              mitigations = mitigations(root.path("mitigations")),
            ),
        )
      )
    }
  }

  private fun changes(node: JsonNode): Map<String, List<Change>> =
    node
      .takeIf { it.isObject }
      ?.properties()
      ?.associate { (file, set) ->
        file to
          set
            .takeIf { it.isObject }
            ?.properties()
            ?.mapNotNull { (path, pair) ->
              val was = scalar(pair.path("was"))
              val now = scalar(pair.path("now"))
              if (was == null || now == null) null else Change(path, was, now)
            }
            .orEmpty()
      }
      .orEmpty()

  private fun mitigations(node: JsonNode): MitigationEdit? {
    if (!node.isObject) return null
    val rules = node.path("rules").takeIf { it.isArray }?.values()?.map { plain(it) }
    val edit = MitigationEdit(rules)
    return if (edit.empty) null else edit
  }

  private fun plain(node: JsonNode): Any? =
    when {
      node.isObject -> node.properties().associate { (key, child) -> key to plain(child) }
      node.isArray -> node.values().map { plain(it) }
      node.isBoolean -> node.asBoolean(false)
      node.isNumber -> if (node.isIntegralNumber) node.asLong(0L) else node.asDouble(0.0)
      node.isNull -> null
      else -> node.asString("")
    }

  private fun regions(node: JsonNode): Map<String, List<String>>? =
    node
      .takeIf { it.isObject }
      ?.properties()
      ?.associate { (world, list) -> world to list.mapNotNull { scalar(it) } }

  private fun strings(node: JsonNode): Map<String, String> =
    node
      .takeIf { it.isObject }
      ?.properties()
      ?.associate { (key, value) -> key to value.asString("") }
      .orEmpty()

  private fun scalar(node: JsonNode): String? =
    when {
      node.isMissingNode || node.isNull -> null
      node.isTextual -> node.asString("")
      node.isNumber || node.isBoolean -> node.asString("")
      else -> null
    }
}
