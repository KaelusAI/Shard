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
import ac.shard.connect.CredentialsStore
import ac.shard.editor.EditorSnapshot
import ac.shard.editor.SessionKind

sealed interface OpenResult {
  data class Opened(
    val sessionId: String,
    val userCode: String,
    val url: String,
    val expiresInSeconds: Long,
    val pollIntervalSeconds: Long,
  ) : OpenResult

  data class Error(val message: String) : OpenResult
}

sealed interface SessionPoll {
  data object Pending : SessionPoll

  data class Saved(val payload: String) : SessionPoll

  data object Expired : SessionPoll

  data class Error(val message: String) : SessionPoll
}

private const val BASE = "/api/v1/device/session"
private const val HTTP_OK = 200
private const val UNAUTHORIZED = 401
private const val GONE = 410
private const val TOO_MANY_REQUESTS = 429

private val GONE_ERRORS = setOf("invalid_session_id", "session_not_found", "expired_session")
private val GONE_STATUSES = setOf("expired", "cancelled", "canceled")
private const val UNKNOWN_ENDPOINT = "unknown_endpoint"
private const val OUT_OF_STEP =
  "The panel does not know this request. Shard and the panel are out of step - update the plugin."

private const val EDITOR_MIN_SECONDS = 60L
private const val EDITOR_MAX_SECONDS = 3_600L
private const val EDITOR_DEFAULT_SECONDS = 1_800L
private const val SETUP_MIN_SECONDS = 300L
private const val SETUP_MAX_SECONDS = 604_800L
private const val SETUP_DEFAULT_SECONDS = 86_400L
private const val MIN_POLL_SECONDS = 1L
private const val MAX_POLL_SECONDS = 300L
private const val DEFAULT_POLL_SECONDS = 5L

@Suppress("TooManyFunctions", "LongParameterList")
internal class PanelSessionService(
  private val plugin: Shard,
  private val client: PanelClient,
  private val credentialsStore: CredentialsStore,
) {

  fun open(
    kind: SessionKind,
    actor: Map<String, Any?>,
    environment: Map<String, Any?>,
    snapshot: EditorSnapshot,
  ): OpenResult {
    val reply =
      client.post(
        "$BASE/open",
        mapOf(
          "kind" to kind.name.lowercase(),
          "instance_id" to credentialsStore.instanceId(),
          "plugin_version" to plugin.description.version,
          "actor" to actor,
          "env" to environment,
          "snapshot" to snapshot.files.associate { file -> file.name to fields(file) },
          "disabled_regions" to snapshot.disabledRegions,
          "punishments" to snapshot.punishments,
          "mitigations" to snapshot.mitigations,
          "baseline" to snapshot.files.associate { it.name to it.baseline },
        ),
      ) ?: return OpenResult.Error("The panel is not reachable.")
    return read(kind, reply)
  }

  fun poll(sessionId: String): SessionPoll =
    outcome(client.post("$BASE/poll", mapOf("session_id" to sessionId)))

  fun claim(userCode: String): SessionPoll =
    outcome(client.post("$BASE/claim", mapOf("user_code" to userCode)))

  fun holding(sessionId: String, resultId: String, secondsLeft: Long) {
    client.post(
      "$BASE/ack",
      mapOf(
        "session_id" to sessionId,
        "result_id" to resultId,
        "status" to "pending_confirmation",
        "expires_in" to secondsLeft,
        "reasons" to emptyList<Map<String, String>>(),
      ),
    )
  }

  fun ack(sessionId: String, resultId: String, refusals: List<String> = emptyList()) {
    val applied = refusals.isEmpty()
    val body =
      mapOf(
        "session_id" to sessionId,
        "result_id" to resultId,
        "status" to if (applied) "applied" else "refused",
        "reasons" to refusals.map { reason -> mapOf("code" to codeOf(reason), "text" to reason) },
      )
    val reply = client.post("$BASE/ack", body)
    if (reply != null && reply.status != HTTP_OK) {
      val what = if (applied) "The change was applied" else "The change was refused"
      plugin.logger.warning(
        "[Panel] $what, but the panel did not accept the acknowledgement " +
          "(HTTP ${reply.status} ${reply.node.path("error").asText("")}). " +
          "The session will expire on its own."
      )
    }
  }

  private fun codeOf(reason: String): String =
    when {
      reason.contains("reopen the editor") -> "BASELINE_MISMATCH"
      reason.contains("is not editable") -> "UNKNOWN_KEY"
      reason.contains("is not in the file") -> "MISSING_KEY"
      reason.contains("could not be read") -> "FILE_UNREADABLE"
      reason.contains("would stop being readable") -> "WOULD_BREAK_FILE"
      reason.contains("not a formatting tag") || reason.contains("only [alert]") -> "ACTION_REFUSED"
      reason.contains("from the console only") -> "CONSOLE_ONLY"
      else -> "REFUSED"
    }

  fun cancel(sessionId: String) {
    client.post("$BASE/cancel", mapOf("session_id" to sessionId))
  }

  private fun fields(file: ac.shard.editor.FileSnapshot) =
    file.fields.associate { it.path to it.value }

  private fun read(kind: SessionKind, reply: PanelReply): OpenResult {
    val node = reply.node
    val sessionId = node.path("session_id").asText("")
    return when {
      node.path("error").asText("") == UNKNOWN_ENDPOINT -> OpenResult.Error(OUT_OF_STEP)
      reply.status == UNAUTHORIZED ->
        OpenResult.Error("The panel did not accept this server's key. Run /shard connect again.")
      reply.status == TOO_MANY_REQUESTS ->
        OpenResult.Error("Too many attempts. Please wait a few minutes.")
      reply.status != HTTP_OK -> OpenResult.Error("The panel returned HTTP ${reply.status}.")
      sessionId.isBlank() -> OpenResult.Error("The panel returned no session.")
      else ->
        OpenResult.Opened(
          sessionId = sessionId,
          userCode = node.path("user_code").asText(""),
          url = node.path("verification_uri_complete").asText(""),
          expiresInSeconds = window(kind, node.path("expires_in").asLong(-1)),
          pollIntervalSeconds =
            node
              .path("poll_interval")
              .asLong(DEFAULT_POLL_SECONDS)
              .coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS),
        )
    }
  }

  private fun window(kind: SessionKind, given: Long): Long =
    if (kind == SessionKind.SETUP) {
      (if (given < 0) SETUP_DEFAULT_SECONDS else given).coerceIn(
        SETUP_MIN_SECONDS,
        SETUP_MAX_SECONDS,
      )
    } else {
      (if (given < 0) EDITOR_DEFAULT_SECONDS else given).coerceIn(
        EDITOR_MIN_SECONDS,
        EDITOR_MAX_SECONDS,
      )
    }

  private fun outcome(reply: PanelReply?): SessionPoll {
    if (reply == null) {
      return SessionPoll.Error("The panel is not reachable.")
    }
    val status = reply.node.path("status").asText("")
    val error = reply.node.path("error").asText("")
    return when {
      reply.status == GONE || status in GONE_STATUSES -> SessionPoll.Expired
      error in GONE_ERRORS -> SessionPoll.Expired
      error == UNKNOWN_ENDPOINT -> SessionPoll.Error(OUT_OF_STEP)
      reply.status == UNAUTHORIZED ->
        SessionPoll.Error("The panel did not accept this server's key. Run /shard connect again.")
      reply.status != HTTP_OK -> SessionPoll.Error("The panel returned HTTP ${reply.status}.")
      status == "pending" -> SessionPoll.Pending
      status == "saved" ->
        client.verifiedPayload(reply.node)?.let(SessionPoll::Saved)
          ?: SessionPoll.Error("The result did not carry a valid signature.")
      else -> SessionPoll.Error("The panel sent an answer Shard does not understand.")
    }
  }
}
