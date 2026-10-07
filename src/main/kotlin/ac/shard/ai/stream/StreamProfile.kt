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

enum class Schedule {
  ATTACK,
  SLIDE,
  CONTINUOUS,
}

data class RoleSpec(val buffer: Double? = null, val labels: Set<String>? = null) {
  fun covers(label: String): Boolean =
    labels == null || label in labels || label == LabelKey.UNATTRIBUTED
}

data class ModelRole(val alert: RoleSpec?, val mitigate: RoleSpec?, val punish: RoleSpec?) {
  companion object {
    val ALL = ModelRole(RoleSpec(), RoleSpec(), RoleSpec())
  }
}

data class BufferSpec(
  val flag: Double,
  val resetOnFlag: Double,
  val multiplier: Double,
  val decrease: Double,
  val maxTracked: Int,
  val labels: Map<String, BufferSpec> = emptyMap(),
  val shared: String? = null,
  val weight: Double = 1.0,
) {
  fun forLabel(label: String): BufferSpec = labels[label] ?: this
}

@Suppress("LongParameterList")
data class ModelSpec(
  val id: String,
  val title: String,
  val shortTitle: String?,
  val schedule: Schedule,
  val pre: Int,
  val post: Int,
  val step: Int,
  val window: Int,
  val stride: Int,
  val anchorKinds: Set<Short>,
  val labels: List<String>,
  val labelMode: LabelMode?,
  val legitLabels: Set<String>,
  val labelTitles: Map<String, String>,
  val thresholds: Map<String, LabelThresholds>,
  val buffer: BufferSpec,
  val mitigationWeight: Double,
  val role: ModelRole,
  val inert: String? = null,
) {
  val singleHead: Boolean
    get() = labels.isEmpty()

  val preRows: Int
    get() = if (schedule == Schedule.ATTACK) pre else window - 1

  val span: Int
    get() = if (schedule == Schedule.ATTACK) pre + post else window

  val cadence: Int
    get() = if (schedule == Schedule.ATTACK) step else stride

  val closeHorizon: Int
    get() = if (schedule == Schedule.ATTACK) post else window

  val displayTitle: String
    get() = shortTitle ?: title

  val selectionKey: List<Any>
    get() = listOf(schedule, pre, post, step, window, stride, anchorKinds)

  fun startOf(end: Long): Long = end - span + 1
}

data class WireColumn(val name: String, val type: TickSchema.WireType, val field: TickSchema.Field)

data class WireSection(
  val columns: List<WireColumn>,
  val maxPre: Int,
  val chunk: Int,
  val maxChunkRows: Int,
) {
  val rowSize: Int = columns.sumOf { it.type.size }

  override fun equals(other: Any?): Boolean =
    other is WireSection &&
      other.columns.map { it.name to it.type } == columns.map { it.name to it.type } &&
      other.maxPre == maxPre &&
      other.chunk == chunk &&
      other.maxChunkRows == maxChunkRows

  override fun hashCode(): Int =
    listOf(columns.map { it.name to it.type }, maxPre, chunk, maxChunkRows).hashCode()

  companion object {
    const val MAX_CHUNK_ROWS = 512
  }
}

@Suppress("LongParameterList")
class StreamProfile(
  val crc: Long,
  private val columns: List<WireColumn>,
  private val chunk: Int,
  private val maxChunkRows: Int,
  val models: List<ModelSpec>,
  val primary: ModelSpec,
  val ringTtlMs: Long,
  val raw: String,
) {
  val active: List<ModelSpec> = models.filter { it.inert == null }
  val wire = WireSection(columns, active.maxOf { it.preRows }, chunk, maxChunkRows)
  val anchorKinds: Set<Short> = active.flatMap { it.anchorKinds }.toSet()
  val windowStartMask: Int = anchorKinds.fold(0) { mask, kind -> mask or (1 shl kind.toInt()) }
  val closeHorizon: Int = active.maxOf { it.closeHorizon }
  val configHeader: String = "%08x".format(crc)
  val mitigateWeightSum: Double =
    active.filter { it.role.mitigate != null }.sumOf { it.mitigationWeight }
  val punishModels: List<ModelSpec> = active.filter { it.role.punish != null }

  fun model(id: String): ModelSpec? = active.firstOrNull { it.id == id }

  fun indexOf(id: String): Int = models.indexOfFirst { it.id == id && it.inert == null }

  fun servable(index: Int): ModelSpec? = models.getOrNull(index)?.takeIf { it.inert == null }

  fun narrowed(off: Set<String>): StreamProfile {
    val cut = off - primary.id
    if (active.none { it.id in cut }) return this
    val narrowed = models.map {
      if (it.inert == null && it.id in cut) it.copy(inert = LOCALLY_OFF) else it
    }
    return StreamProfile(crc, columns, chunk, maxChunkRows, narrowed, primary, ringTtlMs, raw)
  }

  companion object {
    const val LOCALLY_OFF = "disabled in ai.models"
  }
}
