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
package ac.shard.monitor.hud

import ac.shard.api.event.AiPredictionEvent
import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSample
import ac.shard.monitor.core.MonitorSettings
import ac.shard.platform.scheduler.TaskHandle
import java.util.concurrent.atomic.AtomicBoolean
import org.bukkit.entity.Player

data class MonitorSessionSpec(
  val viewer: Player,
  val sessionId: Long,
  val chatStyle: MonitorChatStyle,
  val config: MonitorHudRuntimeConfig,
)

class MonitorHudSession(private val spec: MonitorSessionSpec, outputs: List<MonitorOutput>) {
  val cancelled = AtomicBoolean(false)

  val targets = MonitorTargets()

  val viewer: Player
    get() = spec.viewer

  val sessionId: Long
    get() = spec.sessionId

  val config: MonitorHudRuntimeConfig
    get() = spec.config

  val chatStyle: MonitorChatStyle
    get() = spec.chatStyle

  val context =
    MonitorRenderContext(
      spec.viewer,
      spec.viewer.uniqueId,
      spec.sessionId,
      spec.chatStyle,
      spec.config,
    )

  val outputs: MutableList<MonitorOutput> = outputs.toMutableList()

  private val sendStates = HashMap<MonitorOutputKind, SendState>()

  var task: TaskHandle? = null

  fun trackTarget(target: Player, texts: UnavailableTexts): Boolean =
    targets.add(
      MonitorTargetState(
        targetId = target.uniqueId,
        targetName = target.name,
        texts = texts,
        threshold = spec.config.format.trendThreshold,
        decayCycles = spec.config.behavior.trendDecayCycles,
      )
    )

  fun render(
    samples: List<MonitorSample>,
    settings: MonitorSettings,
    builder: MonitorFrameBuilder,
  ) {
    val frames = samples.mapNotNull { sample ->
      targets.state(sample.targetId)?.let { state ->
        state.advance(
          sample,
          spec.config.behavior.pingRefreshCycles,
          spec.config.behavior.pingBucketMs,
        )
        frameFor(state, sample, settings, builder)
      }
    }
    if (frames.isEmpty()) {
      return
    }
    val settingsHash = settings.hashCode()
    val payload = MonitorRenderPayload(frames, samples.associateBy { it.targetId })
    outputs.toList().forEach { output ->
      val state = sendStates.getOrPut(output.kind) { SendState() }
      if (state.shouldSend(frames, settingsHash, output.policy(spec.config))) {
        output.render(context, payload)
        state.markSent(frames, settingsHash)
      }
    }
  }

  fun dropOutput(kind: MonitorOutputKind): Boolean {
    sendStates.remove(kind)
    return outputs.removeIf { it.kind == kind }
  }

  fun teardown() {
    outputs.forEach {
      it.clear(context)
      it.detach(context)
    }
  }

  fun liveFrame(
    event: AiPredictionEvent,
    settings: MonitorSettings,
    builder: MonitorFrameBuilder,
  ): MonitorFrame? {
    val state = targets.state(event.playerId) ?: return null
    return frameFor(state, sampleOf(event, state), settings, builder)
  }

  private fun frameFor(
    state: MonitorTargetState,
    sample: MonitorSample,
    settings: MonitorSettings,
    builder: MonitorFrameBuilder,
  ): MonitorFrame =
    builder.build(
      MonitorFrameRequest(
        sample = sample,
        settings = settings,
        pingValue = state.ping,
        trend = state.trend,
        selfView = spec.viewer.uniqueId == state.targetId,
        unavailableHeadline = if (sample.dataPresent) state.texts.noAiCheck else state.texts.noData,
      ),
      spec.config,
    )

  private class SendState {
    private var lastFrames: List<MonitorFrame> = emptyList()
    private var lastSettingsHash = 0
    private var cyclesSinceSend = 0

    fun shouldSend(
      frames: List<MonitorFrame>,
      settingsHash: Int,
      policy: MonitorOutputPolicy,
    ): Boolean {
      val changed = frames != lastFrames || settingsHash != lastSettingsHash
      cyclesSinceSend++
      val throttled = policy.minIntervalCycles > 0 && cyclesSinceSend < policy.minIntervalCycles
      val keepAlive = policy.keepAliveCycles > 0 && cyclesSinceSend >= policy.keepAliveCycles
      return !throttled && (changed || keepAlive)
    }

    fun markSent(frames: List<MonitorFrame>, settingsHash: Int) {
      lastFrames = frames
      lastSettingsHash = settingsHash
      cyclesSinceSend = 0
    }
  }

  private fun sampleOf(event: AiPredictionEvent, state: MonitorTargetState): MonitorSample =
    MonitorSample(
      targetId = event.playerId,
      targetName = event.playerName,
      dataPresent = true,
      aiActive = true,
      probability = event.probability,
      buffer = event.bufferAfter,
      rawPing = state.ping,
      damageMultiplier = event.damageMultiplier,
      prob90 = event.prob90,
    )
}
