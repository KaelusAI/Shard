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
package ac.shard.monitor.hud.output

import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.hud.MonitorFrame
import ac.shard.monitor.hud.MonitorHudRuntimeConfig
import ac.shard.monitor.hud.MonitorOutput
import ac.shard.monitor.hud.MonitorOutputCapabilities
import ac.shard.monitor.hud.MonitorOutputPolicy
import ac.shard.monitor.hud.MonitorRenderContext
import ac.shard.monitor.hud.MonitorRenderPayload
import ac.shard.monitor.hud.OutputStates
import ac.shard.monitor.hud.fillFrameTemplate
import java.util.UUID
import org.bukkit.entity.Player

fun interface ChatSink {
  fun send(viewer: Player, raw: String)
}

data class LiveSignal(
  val frame: MonitorFrame,
  val flagged: Boolean,
  val probability: Double,
  val nowMillis: Long,
)

class ChatOutput(private val sink: ChatSink) : MonitorOutput {
  private val states = OutputStates<ChatState>()

  override val kind = MonitorOutputKind.CHAT

  override val capabilities =
    MonitorOutputCapabilities(
      maxTargets = CHAT_MAX_TARGETS,
      claimsClientSlot = false,
      eventDriven = true,
      requiresClear = false,
    )

  override fun isAvailable(): Boolean = true

  override fun policy(config: MonitorHudRuntimeConfig): MonitorOutputPolicy =
    MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = config.chat.summaryCycles)

  override fun attach(context: MonitorRenderContext): Boolean {
    states.put(context, ChatState(context.chatStyle))
    return true
  }

  override fun render(context: MonitorRenderContext, payload: MonitorRenderPayload) {
    val state = states.get(context)
    if (state == null || state.style != MonitorChatStyle.SUMMARY || !context.viewer.isOnline) {
      return
    }
    renderSummary(context, payload, state)
  }

  override fun clear(context: MonitorRenderContext) {
    val state = states.get(context) ?: return
    state.lastSummary = ""
    state.lastLineAt.clear()
  }

  override fun detach(context: MonitorRenderContext) {
    states.remove(context)
  }

  fun deliverLive(context: MonitorRenderContext, signal: LiveSignal): Boolean {
    val state = states.get(context)
    if (state == null || !shouldSendLine(state, context, signal)) {
      return false
    }
    state.lastLineAt[signal.frame.targetId] = signal.nowMillis
    val config = context.config.chat
    val template = if (signal.flagged) config.flaggedTemplate else config.liveTemplate
    sink.send(context.viewer, fillFrameTemplate(template, signal.frame))
    return true
  }

  private fun shouldSendLine(
    state: ChatState,
    context: MonitorRenderContext,
    signal: LiveSignal,
  ): Boolean {
    if (state.style != MonitorChatStyle.LIVE || !context.viewer.isOnline) {
      return false
    }
    val config = context.config.chat
    val loudEnough =
      (signal.flagged && config.alwaysShowFlagged) || signal.probability >= config.minProbability
    val last = state.lastLineAt[signal.frame.targetId] ?: 0L
    return loudEnough && signal.nowMillis - last >= config.cooldownMillis
  }

  private fun renderSummary(
    context: MonitorRenderContext,
    payload: MonitorRenderPayload,
    state: ChatState,
  ) {
    val config = context.config.chat
    val text =
      payload.frames
        .filter { it.dataPresent && it.aiActive }
        .joinToString(separator = "\n") { fillFrameTemplate(config.summaryTemplate, it) }
    if (text.isBlank() || (config.skipUnchanged && text == state.lastSummary)) {
      return
    }
    state.lastSummary = text
    sink.send(context.viewer, text)
  }

  internal class ChatState(val style: MonitorChatStyle) {
    var lastSummary: String = ""
    val lastLineAt = HashMap<UUID, Long>()
  }
}

internal const val CHAT_MAX_TARGETS = 4
