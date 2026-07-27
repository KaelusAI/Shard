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
package ac.shard.monitor.core

internal class MonitorSettingsEntry(initial: MonitorSettings, loaded: Boolean) {
  private val lock = Any()

  @Volatile private var current: MonitorSettings = initial

  @Volatile private var loadedFlag: Boolean = loaded

  private var writerActive: Boolean = false

  val settings: MonitorSettings
    get() = current

  val isLoaded: Boolean
    get() = loadedFlag

  fun publishLoaded(stored: MonitorSettings?) {
    synchronized(lock) {
      if (!loadedFlag) {
        if (stored != null) {
          current = stored
        }
        loadedFlag = true
      }
    }
  }

  fun apply(
    loader: () -> MonitorSettings?,
    mutator: (MonitorSettings) -> MonitorSettings,
  ): MonitorSettings =
    synchronized(lock) {
      if (!loadedFlag) {
        loader()?.let { current = it }
        loadedFlag = true
      }
      val next = mutator(current)
      current = next
      next
    }

  fun claimWriter(): MonitorSettings? =
    synchronized(lock) {
      if (writerActive) {
        null
      } else {
        writerActive = true
        current
      }
    }

  fun nextWrite(written: MonitorSettings): MonitorSettings? =
    synchronized(lock) {
      if (current === written) {
        writerActive = false
        null
      } else {
        current
      }
    }
}
