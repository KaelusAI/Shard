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

import ac.shard.server.AIResponse
import com.fasterxml.jackson.databind.ObjectMapper

class JacksonAiResponseParser : AiResponseParser {
  override fun parse(response: String): AIResponse {
    val root = OBJECT_MAPPER.readTree(response)
    val node = root.get("probability")
    val probability =
      when {
        node == null || node.isNull -> null
        node.isNumber -> node.doubleValue()
        node.isTextual -> node.textValue().toDoubleOrNull()
        else -> null
      } ?: throw IllegalArgumentException("AI response does not contain a valid probability")
    return AIResponse(
      probability,
      expectedColumns(root),
      probabilities(root),
      labels(root),
      stringMap(root, "label_titles"),
      stringList(root, "legit_labels"),
      text(root, "label_mode"),
      text(root, "model_title"),
      thresholds(root),
      namedProbabilities(root),
    )
  }

  private fun labels(root: com.fasterxml.jackson.databind.JsonNode): List<String>? =
    stringList(root, "labels")

  private fun thresholds(
    root: com.fasterxml.jackson.databind.JsonNode
  ): Map<String, Map<String, Double>>? =
    root
      .get("label_thresholds")
      ?.takeIf { it.isObject }
      ?.properties()
      ?.mapNotNull { (label, node) ->
        node
          .takeIf { it.isObject }
          ?.properties()
          ?.filter { it.value.isNumber }
          ?.associate { it.key to it.value.doubleValue() }
          ?.takeIf { it.isNotEmpty() }
          ?.let { label to it }
      }
      ?.toMap()
      ?.takeIf { it.isNotEmpty() }

  private fun text(root: com.fasterxml.jackson.databind.JsonNode, field: String): String? =
    root.get(field)?.takeIf { it.isTextual }?.textValue()?.takeIf(String::isNotBlank)

  private fun stringMap(
    root: com.fasterxml.jackson.databind.JsonNode,
    field: String,
  ): Map<String, String>? =
    root
      .get(field)
      ?.takeIf { it.isObject }
      ?.properties()
      ?.mapNotNull { (key, value) ->
        value.takeIf { it.isTextual }?.textValue()?.takeIf(String::isNotBlank)?.let { key to it }
      }
      ?.toMap()
      ?.takeIf { it.isNotEmpty() }

  private fun namedProbabilities(
    root: com.fasterxml.jackson.databind.JsonNode
  ): Map<String, Double>? =
    root
      .get("probabilities")
      ?.takeIf { it.isObject }
      ?.properties()
      ?.mapNotNull { (key, node) -> node.takeIf { it.isNumber }?.let { key to it.doubleValue() } }
      ?.toMap()
      ?.takeIf { it.isNotEmpty() }

  private fun probabilities(root: com.fasterxml.jackson.databind.JsonNode): List<Double>? =
    root
      .get("probabilities")
      ?.takeIf { it.isArray }
      ?.map { it.takeIf(com.fasterxml.jackson.databind.JsonNode::isNumber)?.doubleValue() }
      ?.takeIf { it.isNotEmpty() && it.all { value -> value != null } }
      ?.filterNotNull()

  private fun expectedColumns(root: com.fasterxml.jackson.databind.JsonNode): List<String>? =
    stringList(root, "expected_columns")

  private fun stringList(
    root: com.fasterxml.jackson.databind.JsonNode,
    field: String,
  ): List<String>? =
    root
      .get(field)
      ?.takeIf { it.isArray }
      ?.mapNotNull { it.takeIf { node -> node.isTextual }?.textValue()?.takeIf(String::isNotBlank) }
      ?.takeIf { it.isNotEmpty() }

  companion object {
    private val OBJECT_MAPPER = ObjectMapper()
  }
}
