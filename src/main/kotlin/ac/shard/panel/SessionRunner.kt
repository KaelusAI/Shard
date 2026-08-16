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

import ac.shard.Shard
import ac.shard.editor.ApplyResult
import ac.shard.editor.DecodeResult
import ac.shard.editor.Delta
import ac.shard.editor.DeltaCodec
import ac.shard.editor.DiffRow
import ac.shard.editor.EditorApply
import ac.shard.editor.EditorDiff
import ac.shard.editor.EditorSession
import ac.shard.editor.EditorSessionStore
import ac.shard.editor.EditorSnapshotBuilder
import ac.shard.editor.PunishmentActionRule
import ac.shard.editor.ResultGuard
import ac.shard.editor.SessionKind
import ac.shard.editor.Verdict
import ac.shard.scheduler.SchedulerService
import java.time.Instant
import java.util.UUID

sealed interface StartOutcome {
  data class Started(val url: String, val userCode: String, val session: EditorSession) :
    StartOutcome

  data class Busy(val openedBy: UUID?) : StartOutcome

  data class Error(val message: String) : StartOutcome
}

sealed interface FetchOutcome {
  data object NoSession : FetchOutcome

  data object Waiting : FetchOutcome

  data object Gone : FetchOutcome

  data class Ready(val rows: List<DiffRow>, val token: PendingApply) : FetchOutcome

  data class Error(val message: String) : FetchOutcome
}

data class PendingApply(
  val sessionId: String,
  val resultId: String,
  val payload: String,
  val needsConfirming: Boolean,
  val carriesCommands: Boolean,
)

private const val NOT_CONFIRMED = "nobody on the server confirmed this in time"

@Suppress("TooManyFunctions", "LongParameterList")
internal class SessionRunner(
  private val plugin: Shard,
  private val sessions: PanelSessionService,
  private val snapshots: EditorSnapshotBuilder,
  private val apply: EditorApply,
  private val guard: ResultGuard,
  private val scheduler: SchedulerService,
  private val stores: Map<SessionKind, EditorSessionStore>,
) {

  fun start(kind: SessionKind, actor: UUID?, actorName: String): StartOutcome {
    val store = stores.getValue(kind)
    val live = store.read()
    if (live != null && Instant.now().isBefore(live.deadline)) {
      return StartOutcome.Busy(live.openedBy)
    }
    val snapshot = snapshots.build()
    val opened =
      sessions.open(
        kind,
        mapOf(
          "uuid" to actor?.toString(),
          "name" to actorName,
          "source" to if (actor == null) "console" else "player",
        ),
        environment(),
        snapshot,
      )
    return when (opened) {
      is OpenResult.Error -> StartOutcome.Error(opened.message)
      is OpenResult.Opened -> {
        val now = Instant.now()
        val session =
          EditorSession(
            kind = kind,
            sessionId = opened.sessionId,
            openedBy = actor,
            openedFromConsole = actor == null,
            deadline = now.plusSeconds(opened.expiresInSeconds),
            pollUntil = now.plusSeconds(opened.expiresInSeconds),
            baseline = snapshot.files.associate { it.name to it.baseline },
          )
        store.write(session)
        StartOutcome.Started(opened.url, opened.userCode, session)
      }
    }
  }

  fun fetch(kind: SessionKind): FetchOutcome {
    val session = stores.getValue(kind).read() ?: return FetchOutcome.NoSession
    return read(kind, session.sessionId, sessions.poll(session.sessionId))
  }

  fun claim(kind: SessionKind, userCode: String): FetchOutcome =
    read(kind, stores.getValue(kind).read()?.sessionId, sessions.claim(userCode))

  private fun read(kind: SessionKind, expected: String?, poll: SessionPoll): FetchOutcome =
    when (poll) {
      SessionPoll.Pending -> FetchOutcome.Waiting
      SessionPoll.Expired -> {
        stores.getValue(kind).clear()
        FetchOutcome.Gone
      }
      is SessionPoll.Error -> FetchOutcome.Error(poll.message)
      is SessionPoll.Saved -> ready(expected, poll.payload)
    }

  fun holding(pending: PendingApply, secondsLeft: Long) {
    sessions.holding(pending.sessionId, pending.resultId, secondsLeft)
  }

  fun abandon(kind: SessionKind, pending: PendingApply) {
    refuse(kind, pending, NOT_CONFIRMED)
  }

  fun refuse(kind: SessionKind, pending: PendingApply, reason: String) {
    sessions.ack(pending.sessionId, pending.resultId, listOf(reason))
    stores.getValue(kind).clear()
  }

  fun commit(kind: SessionKind, pending: PendingApply): ApplyResult {
    val decoded = DeltaCodec.decode(pending.payload)
    val result =
      if (decoded is DecodeResult.Decoded) {
        apply.apply(decoded.result.delta, decoded.result.baseline)
      } else {
        ApplyResult.Refused(listOf("the panel sent a result Shard could not read"))
      }
    when (result) {
      is ApplyResult.Applied -> {
        sessions.ack(pending.sessionId, pending.resultId)
        stores.getValue(kind).clear()
        scheduler.runSync { plugin.onReload() }
      }
      is ApplyResult.Refused -> {
        sessions.ack(pending.sessionId, pending.resultId, result.reasons)
        stores.getValue(kind).clear()
      }
      is ApplyResult.RolledBack ->
        sessions.ack(pending.sessionId, pending.resultId, listOf(result.reason))
    }
    return result
  }

  fun backups(): List<String> = apply.backups()

  fun restore(stamp: String): ApplyResult {
    val result = apply.restore(stamp)
    if (result is ApplyResult.Applied) scheduler.runSync { plugin.onReload() }
    return result
  }

  fun cancel(kind: SessionKind) {
    stores.getValue(kind).read()?.let { sessions.cancel(it.sessionId) }
    stores.getValue(kind).clear()
  }

  fun session(kind: SessionKind): EditorSession? = stores.getValue(kind).read()

  private fun ready(expected: String?, payload: String): FetchOutcome {
    val decoded = DeltaCodec.decode(payload)
    if (decoded !is DecodeResult.Decoded) {
      return FetchOutcome.Error((decoded as DecodeResult.Malformed).reason)
    }
    val result = decoded.result
    val fresh = guard.accept(result.resultId, result.issuedAt)
    return when {
      fresh is Verdict.Refused -> FetchOutcome.Error(fresh.reason)
      expected != null && result.sessionId != expected ->
        FetchOutcome.Error("the result belongs to another session")
      else -> {
        val rows = EditorDiff.rows(result.delta)
        FetchOutcome.Ready(
          rows,
          PendingApply(
            result.sessionId,
            result.resultId,
            payload,
            EditorDiff.needsConfirming(rows),
            carriesCommands(result.delta),
          ),
        )
      }
    }
  }

  private fun carriesCommands(delta: Delta): Boolean =
    delta.punishments.orEmpty().any { edit ->
      edit.actions.values.flatten().any(PunishmentActionRule::needsConfirming)
    }

  private fun environment(): Map<String, Any?> =
    mapOf(
      "platform" to plugin.server.name,
      "mc" to plugin.server.bukkitVersion,
      "worldguard" to (plugin.server.pluginManager.getPlugin("WorldGuard") != null),
      "worlds" to plugin.server.worlds.map { it.name },
    )
}
