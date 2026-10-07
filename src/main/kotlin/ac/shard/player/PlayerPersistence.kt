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
package ac.shard.player

import ac.shard.detection.AiSnapshotStore
import ac.shard.detection.PersistentBufferService
import ac.shard.mitigation.MitigationLogStore
import ac.shard.mitigation.MitigationScoreStore
import ac.shard.scheduler.SchedulerService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PlayerPersistence(
  private val buffers: PersistentBufferService,
  private val scores: MitigationScoreStore,
  private val mitigations: MitigationLogStore,
  private val snapshots: AiSnapshotStore,
  private val directory: PlayerDirectory,
  private val scheduler: SchedulerService,
) {
  private val pending = ConcurrentHashMap.newKeySet<ShardPlayer>()
  private val saving = AtomicInteger()

  fun restore(player: ShardPlayer, name: String, onReady: () -> Unit) {
    val uuid = player.uuid
    val at = System.currentTimeMillis()
    scheduler.runAsync { directory.remember(uuid, name, at) }
    buffers.restoreOnLogin(player, onReady)
    scores.restoreOnLogin(player)
  }

  fun saveLater(player: ShardPlayer) {
    // runAsync only starts on the next heartbeat, so saveAll picks up what is still pending.
    pending.add(player)
    scheduler.runAsync {
      saving.incrementAndGet()
      try {
        if (pending.remove(player)) saveOnce(player)
      } finally {
        saving.decrementAndGet()
      }
    }
  }

  fun saveAll(attached: Collection<ShardPlayer>) {
    attached.forEach(::saveOnce)
    for (player in pending.toList()) {
      if (pending.remove(player)) saveOnce(player)
    }
    val deadline = System.currentTimeMillis() + SAVE_WAIT_MILLIS
    while (saving.get() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(SAVE_POLL_MILLIS)
    }
  }

  private fun saveOnce(player: ShardPlayer) {
    if (!player.persisted.compareAndSet(false, true)) return
    buffers.saveOnQuit(player)
    scores.save(player)
    mitigations.saveOnQuit(player)
    snapshots.saveOnQuit(player)
  }

  private companion object {
    const val SAVE_WAIT_MILLIS = 5_000L
    const val SAVE_POLL_MILLIS = 10L
  }
}
