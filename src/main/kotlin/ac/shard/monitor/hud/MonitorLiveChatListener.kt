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

import ac.shard.detection.Prediction
import ac.shard.detection.Predictions
import ac.shard.monitor.core.ModelFilter
import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorLabelInfo
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSettingsService
import ac.shard.monitor.hud.output.ChatOutput
import ac.shard.monitor.hud.output.LiveSignal
import ac.shard.player.PlayerDataManager
import ac.shard.scheduler.SchedulerService
import java.util.logging.Level
import java.util.logging.Logger

@Suppress("LongParameterList")
class MonitorLiveChatListener(
  private val hudService: MonitorHudService,
  private val index: MonitorTargetIndex,
  private val chatOutput: ChatOutput,
  private val settingsService: MonitorSettingsService,
  private val frameBuilder: MonitorFrameBuilder,
  private val scheduler: SchedulerService,
  private val playerDataManager: PlayerDataManager,
  private val logger: Logger,
) {
  fun register(predictions: Predictions) {
    predictions.listen(::onPrediction)
  }

  fun onPrediction(event: Prediction) {
    for (viewerId in index.viewersOf(event.playerId)) {
      val session = hudService.session(viewerId)
      if (session != null && wantsLiveChat(session)) {
        scheduler.runSync(session.viewer) { deliver(session, event) }
      }
    }
  }

  private fun wantsLiveChat(session: MonitorHudSession): Boolean =
    session.outputs.any { it.kind == MonitorOutputKind.CHAT } &&
      session.chatStyle == MonitorChatStyle.LIVE

  @Suppress("TooGenericExceptionCaught")
  private fun deliver(session: MonitorHudSession, event: Prediction) {
    try {
      val settings = settingsService.getSettings(session.viewer.uniqueId)
      val target = playerDataManager.getPlayer(event.playerId)
      val tier = target?.mitigation?.appliedTier?.name ?: "NONE"
      val model =
        if (event.primary) {
          target?.detection?.shownModelTitle.orEmpty()
        } else {
          event.model
        }
      val frame = session.liveFrame(event, settings, frameBuilder, tier, model)
      if (frame != null && ModelFilter.shows(settings.models, event.modelId, event.primary)) {
        val probability =
          if (event.primary) {
            MonitorLabelInfo.probabilityOfLeader(
              event.labelBuffers,
              event.labelProbabilities,
              event.probability,
            )
          } else {
            event.probability
          }
        chatOutput.deliverLive(
          session.context,
          LiveSignal(frame, event.flagged, probability, System.currentTimeMillis(), event.modelId),
        )
      }
    } catch (throwable: Throwable) {
      logger.log(
        Level.WARNING,
        "[Monitor] live chat delivery failed for ${session.viewer.name}",
        throwable,
      )
    }
  }
}
