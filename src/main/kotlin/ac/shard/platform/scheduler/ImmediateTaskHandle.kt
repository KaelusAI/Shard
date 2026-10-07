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
package ac.shard.platform.scheduler

class ImmediateTaskHandle
private constructor(private val sync: Boolean, private val cancelled: Boolean) : TaskHandle {
  override fun isSync(): Boolean = sync

  override fun isCancelled(): Boolean = cancelled

  override fun cancel() = Unit

  companion object {
    @JvmStatic fun sync(): ImmediateTaskHandle = ImmediateTaskHandle(sync = true, cancelled = false)

    @JvmStatic
    fun notScheduled(): ImmediateTaskHandle = ImmediateTaskHandle(sync = true, cancelled = true)
  }
}
