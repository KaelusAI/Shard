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
package ac.shard.command.commands.admin

import ac.shard.editor.ApplyResult
import ac.shard.editor.DiffRow
import ac.shard.editor.DiffWeight
import ac.shard.editor.EditorDiff
import ac.shard.editor.SessionKind
import ac.shard.panel.FetchOutcome
import ac.shard.panel.PendingApply
import ac.shard.panel.SessionRunner
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.Message
import ac.shard.utils.Messages
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.bukkit.command.CommandSender

internal class PanelSessionFlow(
  private val kind: SessionKind,
  private val runner: SessionRunner,
  private val messages: Messages,
  private val scheduler: SchedulerService,
  private val replies: Replies,
) {
  class Replies(
    val applied: Message,
    val rejected: Message,
    val rolledBack: Message,
    val nothingToConfirm: Message,
  )

  class Held(val token: PendingApply, val until: Instant)

  private val watching = AtomicBoolean(false)

  fun hold(token: PendingApply): Held {
    runner.holding(token, CONFIRM_WINDOW_SECONDS)
    return Held(token, Instant.now().plusSeconds(CONFIRM_WINDOW_SECONDS))
  }

  fun confirm(native: CommandSender, held: Held?, commit: (PendingApply) -> Unit) {
    when {
      held == null -> messages.sendMessage(native, replies.nothingToConfirm)
      Instant.now().isAfter(held.until) -> {
        messages.sendMessage(native, replies.nothingToConfirm)
        scheduler.runAsync { runner.abandon(kind, held.token) }
      }
      else -> scheduler.runAsync { commit(held.token) }
    }
  }

  fun commit(token: PendingApply, tell: (send: (CommandSender) -> Unit) -> Unit) {
    when (val result = runner.commit(kind, token)) {
      is ApplyResult.Applied ->
        tell {
          messages.sendMessage(
            it,
            replies.applied,
            "changed",
            result.count.toString(),
            "stamp",
            result.stamp,
          )
        }
      is ApplyResult.Refused ->
        tell { messages.sendMessage(it, replies.rejected, "reason", result.reasons.first()) }
      is ApplyResult.RolledBack ->
        tell { messages.sendMessage(it, replies.rolledBack, "reason", result.reason) }
    }
  }

  fun showDiff(native: CommandSender, rows: List<DiffRow>) {
    messages.sendMessage(native, Message.EDITOR_DIFF_HEADER, "count", rows.size.toString())
    val shown = EditorDiff.visible(rows, MAX_DIFF_ROWS)
    shown.forEach { row ->
      messages.sendMessage(
        native,
        Message.EDITOR_DIFF_LINE,
        "sign",
        if (row.weight == DiffWeight.NOTABLE) "!" else "•",
        "key",
        row.key,
        "old",
        row.before.ifBlank { "-" },
        "new",
        row.after,
      )
    }
    if (rows.size > shown.size) {
      messages.sendMessage(
        native,
        Message.EDITOR_DIFF_MORE,
        "count",
        (rows.size - shown.size).toString(),
      )
    }
  }

  fun watch(stopAt: Instant, delaySeconds: (Instant) -> Long, onEnd: (FetchOutcome?) -> Unit) {
    if (!watching.compareAndSet(false, true)) return
    val startedAt = Instant.now()
    fun next() {
      scheduler.runLaterAsync(
        {
          if (Instant.now().isAfter(stopAt)) {
            watching.set(false)
            onEnd(null)
          } else {
            when (val outcome = runner.fetch(kind)) {
              is FetchOutcome.Waiting,
              is FetchOutcome.Error -> next()
              else -> {
                watching.set(false)
                onEnd(outcome)
              }
            }
          }
        },
        delaySeconds(startedAt) * MILLIS_PER_SECOND,
      )
    }
    next()
  }

  private companion object {
    const val CONFIRM_WINDOW_SECONDS = 600L
    const val MAX_DIFF_ROWS = 20
    const val MILLIS_PER_SECOND = 1000L
  }
}
