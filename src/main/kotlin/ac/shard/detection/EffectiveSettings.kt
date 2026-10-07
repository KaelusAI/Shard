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

import ac.shard.ai.label.LabelMode
import ac.shard.ai.label.LabelThresholds
import ac.shard.ai.stream.BufferSpec
import ac.shard.ai.stream.ModelRole
import ac.shard.ai.stream.ModelSpec
import ac.shard.config.LocalAiSettings

@Suppress("LongParameterList")
class EffectiveSettings(
  val buffer: BufferSpec,
  val labelMode: LabelMode,
  val split: Boolean,
  val names: Map<String, String>,
  val maxTracked: Int,
  val role: ModelRole,
  private val thresholds: Map<String, LabelThresholds>,
) {
  fun thresholds(label: String): LabelThresholds = thresholds[label] ?: DEFAULT_THRESHOLDS

  fun buffer(label: String): BufferSpec = buffer.forLabel(label)

  fun bufferSettings(label: String): ViolationBuffer.Settings {
    val t = thresholds(label)
    val b = buffer(label)
    return ViolationBuffer.Settings(t.cheat, t.legit, b.multiplier, b.decrease)
  }

  companion object {
    private const val CHEAT = 0.9
    private const val LEGIT = 0.1
    val DEFAULT_THRESHOLDS = LabelThresholds(CHEAT, LEGIT)

    fun of(m: ModelSpec, local: LocalAiSettings): EffectiveSettings {
      val mode = if (m.singleHead) LabelMode.SINGLE else m.labelMode ?: LabelMode.MULTI_LABEL
      val off = local.models[m.id]?.disabledRoles.orEmpty()
      val role =
        ModelRole(
          alert = m.role.alert.takeIf { "alert" !in off },
          mitigate = m.role.mitigate.takeIf { "mitigate" !in off },
          punish = m.role.punish.takeIf { "punish" !in off },
        )
      return EffectiveSettings(
        buffer = m.buffer,
        labelMode = mode,
        split = local.split,
        names = local.names,
        maxTracked = m.buffer.maxTracked,
        role = role,
        thresholds = if (m.singleHead) emptyMap() else m.thresholds,
      )
    }
  }
}
