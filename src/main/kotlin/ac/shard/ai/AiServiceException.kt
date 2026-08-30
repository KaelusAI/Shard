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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package ac.shard.ai

@Suppress("LongParameterList")
class AiServiceException(
  cause: Throwable,
  val newPreWindow: Int?,
  val newPostWindow: Int?,
  val newStep: Int?,
  val newColumns: List<String>? = null,
  val newLabels: List<String>? = null,
  val newLabelNames: Map<String, String>? = null,
  val newLegitLabels: List<String>? = null,
  val newLabelMode: String? = null,
  val newLabelThresholds: Map<String, Map<String, Double>>? = null,
  val newModelTitle: String? = null,
  val newModel: String? = null,
) : RuntimeException(cause.message, cause) {
  val hasNewParams: Boolean
    get() =
      sequenceOf(
          newPreWindow,
          newPostWindow,
          newStep,
          newColumns,
          newLabels,
          newLabelNames,
          newLegitLabels,
          newLabelMode,
          newLabelThresholds,
          newModelTitle,
          newModel,
        )
        .any { it != null }
}
