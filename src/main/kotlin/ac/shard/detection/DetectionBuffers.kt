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
import ac.shard.ai.label.LabelKey
import ac.shard.ai.label.LabelledVerdict
import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.RoleSpec
import ac.shard.ai.stream.StreamProfile

data class FeedResult(
  val crossed: Map<String, Double>,
  val before: Double,
  val after: Double,
  val labelsBefore: Map<String, Double> = emptyMap(),
  val labelsAfter: Map<String, Double> = emptyMap(),
) {
  fun before(role: RoleSpec): Double = peak(labelsBefore, role)

  fun after(role: RoleSpec): Double = peak(labelsAfter, role)

  private fun peak(values: Map<String, Double>, role: RoleSpec): Double =
    values.filterKeys(role::covers).values.maxOrNull() ?: 0.0
}

@Suppress("TooManyFunctions")
class DetectionBuffers {
  private val lock = Any()
  private val buffers = LinkedHashMap<DetectionKey, ViolationBuffer>()

  fun feed(
    model: ModelSpec,
    verdict: LabelledVerdict,
    s: EffectiveSettings,
    weight: Double = 1.0,
    shared: Boolean = false,
  ): FeedResult =
    synchronized(lock) {
      val labelsBefore = labelsOf(model.id)
      val crossed = LinkedHashMap<String, Double>()
      for ((label, probability) in verdict.values) {
        val spec = s.buffer(label)
        val buffer =
          bufferFor(DetectionKey(model.id, label), s.maxTracked, s.buffer.flag) ?: continue
        val base = s.bufferSettings(label)
        buffer.feed(
          probability,
          ViolationBuffer.Settings(
            base.cheatProbability,
            base.legitProbability,
            base.multiplier * weight,
            base.decrease * weight,
          ),
        )
        if (buffer.value > spec.flag) {
          crossed[label] = buffer.value
          buffer.consumeFlag(spec.resetOnFlag)
        }
      }
      if (verdict.attributed && !shared) retireAbsent(model, verdict, s)
      val labelsAfter = labelsOf(model.id)
      FeedResult(
        crossed,
        labelsBefore.values.maxOrNull() ?: 0.0,
        labelsAfter.values.maxOrNull() ?: 0.0,
        labelsBefore,
        labelsAfter + crossed,
      )
    }

  private fun bufferFor(key: DetectionKey, maxTracked: Int, flag: Double): ViolationBuffer? {
    val existing = buffers[key]
    val own = buffers.entries.filter { it.key.model == key.model }
    val weakest = own.minByOrNull { it.value.value }
    val full = own.size >= maxTracked
    return when {
      existing != null -> existing
      full && (weakest == null || weakest.value.value >= flag) -> null
      else -> {
        if (full) buffers.remove(weakest!!.key)
        ViolationBuffer().also { buffers[key] = it }
      }
    }
  }

  private fun labelsOf(modelId: String): Map<String, Double> =
    buffers.entries.filter { it.key.model == modelId }.associate { it.key.label to it.value.value }

  private fun retireAbsent(model: ModelSpec, verdict: LabelledVerdict, s: EffectiveSettings) {
    val iterator = buffers.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.key.model != model.id || entry.key.label in verdict.values) continue
      val label = entry.key.label
      val undeclared =
        model.labels.isNotEmpty() && label !in model.labels && !LabelKey.isReserved(label)
      if (undeclared || entry.value.decay(s.buffer(label).decrease) <= 0.0) iterator.remove()
    }
  }

  private fun maxOf(modelId: String): Double =
    buffers.entries.filter { it.key.model == modelId }.maxOfOrNull { it.value.value } ?: 0.0

  fun snapshot(): Map<DetectionKey, Double> =
    synchronized(lock) { buffers.entries.associate { it.key to it.value.value } }

  fun modelMax(modelId: String): Double = synchronized(lock) { maxOf(modelId) }

  fun activeMax(p: StreamProfile): Double =
    synchronized(lock) {
      val active = p.active.map { it.id }.toSet()
      buffers.entries.filter { it.key.model in active }.maxOfOrNull { it.value.value } ?: 0.0
    }

  fun punishMax(p: StreamProfile): Double =
    synchronized(lock) {
      val punish = p.punishModels.map { it.id }.toSet()
      buffers.entries.filter { it.key.model in punish }.maxOfOrNull { it.value.value } ?: 0.0
    }

  fun reconcile(p: StreamProfile, active: (String) -> Boolean, orphanOwner: String) =
    synchronized(lock) {
      if (p.model(orphanOwner) == null) {
        val orphans = buffers.entries.filter { it.key.model == orphanOwner }
        for ((key, buffer) in orphans) {
          buffers.remove(key)
          val target = DetectionKey(p.primary.id, key.label)
          val kept = buffers[target]
          if (kept == null || kept.value < buffer.value) buffers[target] = buffer
        }
      }
      buffers.keys.removeIf { p.model(it.model) == null || !active(it.model) }
    }

  fun clear(match: (DetectionKey) -> Boolean): Map<DetectionKey, Double> =
    synchronized(lock) {
      val removed = buffers.entries.filter { match(it.key) }.associate { it.key to it.value.value }
      removed.keys.forEach(buffers::remove)
      removed
    }

  fun restore(entries: List<Pair<DetectionKey, Double>>) =
    synchronized(lock) {
      for ((key, value) in entries) buffers.getOrPut(key) { ViolationBuffer() }.restore(value)
    }

  fun mergeFrom(other: Map<DetectionKey, Double>) = restore(other.toList())
}
