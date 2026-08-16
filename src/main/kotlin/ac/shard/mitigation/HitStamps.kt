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
package ac.shard.mitigation

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TTL_MILLIS = 30_000L
private const val SWEEP_EVERY = 256

data class HitStamp(val owner: UUID, val multiplier: Double)

class HitStamps(private val clock: () -> Long = System::currentTimeMillis) {

  private data class Entry(val stamp: HitStamp, val expiresAt: Long)

  private val entries = ConcurrentHashMap<UUID, Entry>()
  private var sinceSweep = 0

  fun remember(subject: UUID, owner: UUID, multiplier: Double) {
    if (multiplier >= 1.0) return
    entries[subject] = Entry(HitStamp(owner, multiplier), clock() + TTL_MILLIS)
    if (++sinceSweep >= SWEEP_EVERY) {
      sinceSweep = 0
      sweep()
    }
  }

  fun take(subject: UUID): HitStamp? = alive(entries.remove(subject))

  fun peek(subject: UUID): HitStamp? = alive(entries[subject])

  private fun alive(entry: Entry?): HitStamp? =
    if (entry == null || entry.expiresAt < clock()) null else entry.stamp

  fun clear() = entries.clear()

  private fun sweep() {
    val now = clock()
    entries.entries.removeIf { it.value.expiresAt < now }
  }
}
