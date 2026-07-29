/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
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
package ac.shard.utils.latency

interface ILatencyUtils {
  /**
   * Adds a task to be executed when the corresponding transaction ACK is received.
   *
   * @param transaction The transaction ID this task is associated with.
   * @param runnable The task to execute.
   */
  fun addRealTimeTask(transaction: Int, runnable: Runnable)

  /**
   * Adds a task to be executed asynchronously via the player's event loop when the corresponding
   * transaction ACK is received.
   *
   * @param transaction The transaction ID this task is associated with.
   * @param runnable The task to execute.
   */
  fun addRealTimeTaskAsync(transaction: Int, runnable: Runnable)

  /**
   * Processes received transaction ACKs and runs associated tasks.
   *
   * @param receivedTransactionId The ID of the transaction ACK received from the client.
   */
  fun handleNettySyncTransaction(receivedTransactionId: Int)
}
