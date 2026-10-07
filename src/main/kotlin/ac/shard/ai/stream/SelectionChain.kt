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

data class Seed(val lastTaken: Long = 0, val lastAttemptEnd: Long = 0)

data class Attempt(val model: String, val end: Long, val anchor: Long, val kind: Short)

@Suppress("TooManyFunctions")
class SelectionChain(
  models: List<ModelSpec>,
  private val seeds: MutableMap<String, Seed> = HashMap(),
) {
  var models: List<ModelSpec> = models
    private set

  private val history = ArrayDeque<Attempt>()
  private var latestAnchor = 0L

  fun seed(modelId: String): Seed = seeds[modelId] ?: Seed()

  fun onAnchor(a: Long, kind: Short): List<Attempt> {
    latestAnchor = a
    val attempts = ArrayList<Attempt>(models.size)
    for (m in models) {
      if (kind !in m.anchorKinds) continue
      val s = seed(m.id)
      when (m.schedule) {
        Schedule.ATTACK -> attack(m, s, a, kind)?.let(attempts::add)
        Schedule.SLIDE -> slide(m, s, a, kind, attempts)
        Schedule.CONTINUOUS -> Unit
      }
    }
    history.addAll(
      attempts.sortedWith(compareBy({ it.end }, { models.indexOfFirst { m -> m.id == it.model } }))
    )
    return attempts
  }

  private fun attack(m: ModelSpec, s: Seed, a: Long, kind: Short): Attempt? {
    val busy = s.lastTaken != 0L && a < s.lastTaken + m.post
    val end = a + m.post - 1
    val gated = s.lastAttemptEnd != 0L && end - s.lastAttemptEnd < m.step
    return when {
      busy -> null
      gated -> {
        seeds[m.id] = s.copy(lastTaken = a)
        null
      }
      else -> {
        seeds[m.id] = Seed(lastTaken = a, lastAttemptEnd = end)
        Attempt(m.id, end, a, kind)
      }
    }
  }

  private fun slide(m: ModelSpec, s: Seed, a: Long, kind: Short, out: MutableList<Attempt>) {
    var last = s.lastAttemptEnd
    var e = -Math.floorDiv(-a, m.stride.toLong()) * m.stride
    while (e <= a + m.window - 1) {
      if (e > last) {
        out += Attempt(m.id, e, a, kind)
        last = e
      }
      e += m.stride
    }
    seeds[m.id] = s.copy(lastAttemptEnd = last)
  }

  fun onTick(t: Long): List<Attempt> {
    val attempts =
      models
        .filter { it.schedule == Schedule.CONTINUOUS && t % it.stride == 0L }
        .filter { seed(it.id).lastAttemptEnd < t }
        .map { m ->
          seeds[m.id] = seed(m.id).copy(lastAttemptEnd = t)
          Attempt(m.id, t, t, CONTINUOUS_KIND)
        }
    history.addAll(attempts)
    return attempts
  }

  fun attemptsEndingIn(lo: Long, hi: Long): List<Attempt> = history.filter { it.end in lo..hi }

  fun hasAttemptEndingAtLeast(anchorFrom: Long, endAtLeast: Long): Boolean = history.any {
    it.anchor >= anchorFrom && it.end >= endAtLeast
  }

  fun latestAnchor(): Long = latestAnchor

  fun pruneBelow(seq: Long) {
    history.removeAll { it.anchor < seq }
  }

  fun adopting(p: StreamProfile): SelectionChain {
    val old = models.associateBy { it.id }
    val kept = HashMap<String, Seed>()
    for (m in p.active) {
      val previous = old[m.id] ?: continue
      if (previous.selectionKey == m.selectionKey) seeds[m.id]?.let { kept[m.id] = it }
    }
    val next = SelectionChain(p.active, kept)
    val ids = p.active.map { it.id }.toSet()
    history.filter { it.model in ids && kept.containsKey(it.model) }.forEach(next.history::add)
    next.latestAnchor = latestAnchor
    return next
  }

  fun progress(modelId: String, t: Long): IntArray? {
    val m = models.firstOrNull { it.id == modelId } ?: return null
    val s = seed(modelId)
    return when (m.schedule) {
      Schedule.ATTACK ->
        if (s.lastTaken != 0L && t < s.lastTaken + m.post) {
          intArrayOf((t - s.lastTaken + 1).toInt(), m.post)
        } else {
          null
        }
      Schedule.CONTINUOUS -> null
      Schedule.SLIDE ->
        if (s.lastAttemptEnd != 0L && t <= s.lastAttemptEnd) {
          intArrayOf((m.stride - (s.lastAttemptEnd - t)).toInt().coerceAtLeast(0), m.stride)
        } else {
          null
        }
    }
  }

  private companion object {
    const val CONTINUOUS_KIND: Short = 0
  }
}
