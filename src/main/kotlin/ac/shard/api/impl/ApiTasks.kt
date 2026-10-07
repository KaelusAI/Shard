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
package ac.shard.api.impl

import ac.shard.api.ShardStorageException
import ac.shard.database.strictStorage
import ac.shard.scheduler.SchedulerService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.plugin.IllegalPluginAccessException

class ApiTasks(private val scheduler: SchedulerService) {
  private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

  @Volatile
  var closed = false
    private set

  @Suppress("TooGenericExceptionCaught")
  fun <T> supply(block: () -> T): CompletableFuture<T> = track { future ->
    scheduler.runAsync {
      try {
        future.complete(strictStorage(block))
      } catch (error: IllegalArgumentException) {
        future.completeExceptionally(error)
      } catch (error: Exception) {
        future.completeExceptionally(ShardStorageException("Shard storage operation failed", error))
      }
    }
  }

  @Suppress("TooGenericExceptionCaught")
  fun <T> onMain(block: (CompletableFuture<T>) -> Unit): CompletableFuture<T> = track { future ->
    scheduler.runSync {
      try {
        block(future)
      } catch (error: Exception) {
        future.completeExceptionally(error)
      }
    }
  }

  fun checkOpen() {
    check(!closed) { DISABLED }
  }

  fun close() {
    closed = true
    for (future in pending.toList()) future.completeExceptionally(IllegalStateException(DISABLED))
  }

  private fun <T> track(start: (CompletableFuture<T>) -> Unit): CompletableFuture<T> {
    checkOpen()
    val future = CompletableFuture<T>()
    pending += future
    future.whenComplete { _, _ -> pending -= future }
    try {
      start(future)
    } catch (e: IllegalPluginAccessException) {
      future.completeExceptionally(IllegalStateException(DISABLED, e))
    }
    if (closed) future.completeExceptionally(IllegalStateException(DISABLED))
    return future
  }

  private companion object {
    const val DISABLED = "Shard is disabled"
  }
}
