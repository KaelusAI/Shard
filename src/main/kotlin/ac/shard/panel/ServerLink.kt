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

import ac.shard.ShardReloader
import ac.shard.connect.ConnectService
import ac.shard.connect.Credentials
import ac.shard.connect.CredentialsStore
import ac.shard.connect.DeviceFlowEnd
import ac.shard.connect.DeviceFlowPoller
import ac.shard.connect.LinkIntent
import ac.shard.connect.LinkResult
import ac.shard.connect.RevokeResult
import ac.shard.connect.StartResult
import ac.shard.scheduler.SchedulerService
import java.net.InetAddress
import java.time.Instant

sealed interface LinkStep {
  data class NeedsApproval(val url: String, val plainUrl: String, val userCode: String) : LinkStep

  data class Linked(val serverName: String) : LinkStep

  data object Denied : LinkStep

  data object Expired : LinkStep

  data class Failed(val message: String) : LinkStep
}

@Suppress("TooManyFunctions")
internal class ServerLink(
  private val connectService: ConnectService,
  private val credentialsStore: CredentialsStore,
  private val reloader: ShardReloader,
  private val scheduler: SchedulerService,
  private val pending: PendingLinkStore,
  private val poller: DeviceFlowPoller,
) {
  private class Flow(val intent: LinkIntent?) {
    @Volatile var deviceCode: String? = null
  }

  private val lock = Any()
  @Volatile private var current: Flow? = null

  fun credentials(): Credentials? = credentialsStore.read()

  fun isLinked(): Boolean = credentials() != null

  fun pendingIntent(): LinkIntent? = current?.intent

  fun begin(intent: LinkIntent, report: (LinkStep) -> Unit): Boolean {
    val flow = Flow(intent)
    val replaced = claim(flow)
    scheduler.runAsync {
      when (val started = connectService.start(credentialsStore.instanceId(), intent)) {
        is StartResult.Error -> if (complete(flow, null)) report(LinkStep.Failed(started.message))
        is StartResult.Started -> {
          val deadline = Instant.now().epochSecond + started.expiresInSeconds
          if (adopt(flow, started, deadline)) {
            report(
              LinkStep.NeedsApproval(
                started.verificationUriComplete,
                started.verificationUri.ifBlank { started.verificationUriComplete },
                started.userCode,
              )
            )
            waitFor(flow, started.deviceCode, started.intervalSeconds, deadline, report)
          } else {
            connectService.cancel(started.deviceCode)
          }
        }
      }
    }
    return replaced
  }

  fun resume(report: (LinkStep) -> Unit): Boolean {
    val saved = pending.read()
    val now = Instant.now().epochSecond
    if (saved != null && saved.deadlineEpochSec <= now) pending.clear()
    val live = saved?.takeIf { it.deadlineEpochSec > now } ?: return false
    val flow = Flow(LinkIntent.SETUP).apply { deviceCode = live.deviceCode }
    val adopted = synchronized(lock) { (current == null).also { free -> if (free) current = flow } }
    if (adopted) {
      waitFor(flow, live.deviceCode, live.intervalSeconds, live.deadlineEpochSec, report)
    }
    return adopted
  }

  fun redeem(userCode: String, done: (LinkResult) -> Unit) {
    val flow = Flow(null)
    claim(flow)
    scheduler.runAsync {
      val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
      val result = connectService.redeem(userCode, credentialsStore.instanceId(), hostname)
      val linked = (result as? LinkResult.Linked)?.credentials
      if (complete(flow, linked)) {
        if (linked != null) scheduler.runSync(reloader::reloadConnection)
        done(result)
      }
    }
  }

  fun unlink(canRevoke: Boolean, done: (revoked: Boolean) -> Unit): Boolean {
    val credentials =
      synchronized(lock) {
        claim(null)
        credentialsStore.read()?.also { credentialsStore.clear() }
      }
    if (credentials != null) {
      scheduler.runAsync {
        val revoked =
          canRevoke && connectService.revoke(credentials.secretKey) !is RevokeResult.Error
        scheduler.runSync(reloader::reloadConnection)
        done(revoked)
      }
    }
    return credentials != null
  }

  fun cancel(intent: LinkIntent): Boolean =
    synchronized(lock) {
      val ours = current?.intent == intent
      if (ours) claim(null)
      ours
    }

  private fun claim(flow: Flow?): Boolean =
    synchronized(lock) {
      val previous = current
      current = flow
      val stale = previous?.deviceCode ?: pending.read()?.deviceCode
      pending.clear()
      stale?.let { code -> scheduler.runAsync { connectService.cancel(code) } }
      previous != null
    }

  private fun adopt(flow: Flow, started: StartResult.Started, deadline: Long): Boolean =
    synchronized(lock) {
      val ours = current === flow
      if (ours) {
        flow.deviceCode = started.deviceCode
        if (flow.intent == LinkIntent.SETUP) {
          pending.write(
            PendingLink(
              deviceCode = started.deviceCode,
              userCode = started.userCode,
              url = started.verificationUriComplete,
              deadlineEpochSec = deadline,
              intervalSeconds = started.intervalSeconds,
            )
          )
        }
      }
      ours
    }

  private fun complete(flow: Flow, linked: Credentials?): Boolean =
    synchronized(lock) {
      val ours = current === flow
      if (ours) {
        current = null
        pending.clear()
        linked?.let(credentialsStore::write)
      }
      ours
    }

  private fun waitFor(
    flow: Flow,
    deviceCode: String,
    intervalSeconds: Long,
    deadlineEpochSec: Long,
    report: (LinkStep) -> Unit,
  ) {
    poller.await(deviceCode, intervalSeconds, deadlineEpochSec, { current === flow }) { end ->
      val approved = (end as? DeviceFlowEnd.Approved)?.result?.credentials
      if (complete(flow, approved)) {
        if (approved != null) scheduler.runSync(reloader::reloadConnection)
        report(
          when (end) {
            is DeviceFlowEnd.Approved -> LinkStep.Linked(approved?.serverName ?: "your server")
            DeviceFlowEnd.Denied -> LinkStep.Denied
            DeviceFlowEnd.Expired,
            DeviceFlowEnd.TimedOut -> LinkStep.Expired
          }
        )
      }
    }
  }
}
