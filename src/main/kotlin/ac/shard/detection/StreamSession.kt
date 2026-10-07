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

import ac.shard.ai.stream.BlockReason
import ac.shard.ai.stream.InferenceStream
import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.StreamProfile
import ac.shard.ai.stream.StreamTransport
import ac.shard.data.TickData
import ac.shard.player.ShardPlayer
import ac.shard.region.RegionCheckMode

class StreamSession(
  private val shardPlayer: ShardPlayer,
  private val services: InferenceServices,
  private val state: DetectionState,
  private val inDisabledRegion: () -> Boolean,
  private val sink: (StreamProfile, ModelSpec, DoubleArray) -> Unit,
) {
  private val configManager = services.configManager
  private var stream: InferenceStream? = null
  private var transport: StreamTransport? = null
  private var lastMotion: List<Double> = emptyList()
  private var lastActiveSeq = 0L

  val blockReason: BlockReason?
    get() = stream.let { if (it == null) BlockReason.DISABLED else it.blockReason }

  fun onDataTick() {
    val current = services.serverProvider.streamTransport() ?: return
    state.reconcile()
    val riding = shardPlayer.compensatedEntities.self.riding != null
    val scorer = services.mitigationScorer
    if (riding) scorer.freeze(shardPlayer) else scorer.thaw(shardPlayer)
    if (current !== transport) {
      stream?.close()
      stream = open(current)
      transport = current
    }
    val tracking = shardPlayer.tracking
    val anchor = tracking.windowStartKind.takeIf { !riding && tracking.windowStartThisTick }
    val gate =
      when {
        riding -> BlockReason.VEHICLE
        current.isBackingOff() -> BlockReason.COOLDOWN
        configManager.settings.regions.mode == RegionCheckMode.SKIP_DETECTION &&
          inDisabledRegion() -> BlockReason.REGION
        else -> null
      }
    val buffer = shardPlayer.tickBuffer
    val seq = buffer.currentSeq()
    stream?.onRow(seq, tracking.sequenceId, anchor, gate, idle(buffer.rowAt(seq), seq))
  }

  fun progressByModel(): List<Pair<ModelSpec, IntArray?>> {
    val current = stream ?: return emptyList()
    val seq = shardPlayer.tickBuffer.currentSeq()
    val p = state.profile()
    return (listOf(p.primary) + p.active.filter { it.id != p.primary.id }).map {
      it to current.progress(it.id, seq)
    }
  }

  fun close() {
    stream?.close()
    stream = null
    transport = null
  }

  private fun idle(row: TickData, seq: Long): Boolean {
    val motion = listOf(row.x, row.y, row.z, row.yaw.toDouble(), row.pitch.toDouble())
    if (motion != lastMotion) {
      lastMotion = motion
      lastActiveSeq = seq
    }
    return seq - lastActiveSeq >= AFK_TICKS
  }

  private fun open(current: StreamTransport): InferenceStream =
    InferenceStream(
      buffer = { shardPlayer.tickBuffer },
      profiles = { configManager.streamProfile },
      service = current,
      verdicts = services.serverProvider.verdicts,
      sink = { profile, model, _, scores -> sink(profile, model, scores) },
      clock = current::now,
    )

  private companion object {
    const val AFK_TICKS = 600L
  }
}
