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
package ac.shard.panel

import ac.shard.config.ConfigManager
import ac.shard.connect.ConnectService
import ac.shard.connect.Credentials
import ac.shard.connect.CredentialsStore
import ac.shard.connect.LinkIntent
import ac.shard.connect.PollResult
import ac.shard.connect.StartResult
import ac.shard.player.PlayerDataManager
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

sealed interface LinkStep {
  data class NeedsApproval(val url: String, val plainUrl: String, val userCode: String) : LinkStep

  data class Linked(val serverName: String) : LinkStep

  data class Failed(val message: String) : LinkStep
}

private const val MILLIS_PER_SECOND = 1000L

@Suppress("LongParameterList")
internal class ServerLink(
  private val connectService: ConnectService,
  private val credentialsStore: CredentialsStore,
  private val configManager: ConfigManager,
  private val aiServerProvider: AIServerProvider,
  private val playerDataManager: PlayerDataManager,
  private val scheduler: SchedulerService,
  private val pending: PendingLinkStore,
) {

  private val generation = AtomicLong()

  fun isLinked(): Boolean = credentialsStore.read() != null

  fun waiting(): Boolean {
    val saved = pending.read() ?: return false
    return saved.deadlineEpochSec > Instant.now().epochSecond
  }

  fun begin(report: (LinkStep) -> Unit) {
    val mine = generation.incrementAndGet()
    when (val started = connectService.start(credentialsStore.instanceId(), LinkIntent.SETUP)) {
      is StartResult.Error -> report(LinkStep.Failed(started.message))
      is StartResult.Started -> {
        val deadline = Instant.now().epochSecond + started.expiresInSeconds
        pending.write(
          PendingLink(
            deviceCode = started.deviceCode,
            userCode = started.userCode,
            url = started.verificationUriComplete,
            deadlineEpochSec = deadline,
            intervalSeconds = started.intervalSeconds,
          )
        )
        report(
          LinkStep.NeedsApproval(
            started.verificationUriComplete,
            started.verificationUri.ifBlank { started.verificationUriComplete },
            started.userCode,
          )
        )
        waitFor(started, deadline, mine, report)
      }
    }
  }

  fun forget() {
    generation.incrementAndGet()
    pending.read()?.let { connectService.cancel(it.deviceCode) }
    pending.clear()
  }

  fun resume(report: (LinkStep) -> Unit): Boolean {
    val saved = pending.read()
    val left = saved?.let { it.deadlineEpochSec - Instant.now().epochSecond } ?: 0L
    if (saved == null || left <= 0L) {
      if (saved != null) pending.clear()
      return false
    }
    waitFor(
      StartResult.Started(
        deviceCode = saved.deviceCode,
        userCode = saved.userCode,
        verificationUri = saved.url,
        verificationUriComplete = saved.url,
        expiresInSeconds = left,
        intervalSeconds = saved.intervalSeconds,
      ),
      saved.deadlineEpochSec,
      generation.incrementAndGet(),
      report,
    )
    return true
  }

  private fun waitFor(
    started: StartResult.Started,
    deadlineEpochSec: Long,
    mine: Long,
    report: (LinkStep) -> Unit,
  ) {
    scheduler.runLaterAsync(
      { pollOnce(started, deadlineEpochSec, mine, report) },
      started.intervalSeconds.coerceAtLeast(1) * MILLIS_PER_SECOND,
    )
  }

  private fun pollOnce(
    started: StartResult.Started,
    deadlineEpochSec: Long,
    mine: Long,
    report: (LinkStep) -> Unit,
  ) {
    if (generation.get() != mine) {
      return
    }
    if (Instant.now().epochSecond >= deadlineEpochSec) {
      pending.clear()
      report(LinkStep.Failed("The link was not approved in time."))
      return
    }
    when (val result = connectService.poll(started.deviceCode)) {
      PollResult.Pending -> waitFor(started, deadlineEpochSec, mine, report)
      is PollResult.SlowDown -> waitFor(started, deadlineEpochSec, mine, report)
      is PollResult.Approved -> {
        pending.clear()
        report(LinkStep.Linked(store(result)))
      }
      PollResult.Denied -> {
        pending.clear()
        report(LinkStep.Failed("The link was refused in the panel."))
      }
      PollResult.Expired -> {
        pending.clear()
        report(LinkStep.Failed("The link code expired."))
      }
      is PollResult.Error -> waitFor(started, deadlineEpochSec, mine, report)
    }
  }

  private fun store(approved: PollResult.Approved): String {
    credentialsStore.write(
      Credentials(
        secretKey = approved.secretKey,
        serverId = approved.serverId,
        serverName = approved.serverName,
        allowlistedIp = approved.allowlistedIp,
        inferenceUrl = approved.inferenceUrl,
      )
    )
    scheduler.runSync {
      configManager.reloadConfig()
      aiServerProvider.reload()
      playerDataManager.reloadAllPlayers()
    }
    return approved.serverName ?: "your server"
  }
}
