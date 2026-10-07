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
package ac.shard.server

import ac.shard.ai.stream.ChunkWriter
import ac.shard.ai.stream.ProbeBackoff
import ac.shard.ai.stream.StreamErrorLog
import ac.shard.ai.stream.StreamTransport
import ac.shard.config.AiConnection
import ac.shard.config.ConfigManager
import ac.shard.connect.CredentialsStore
import ac.shard.http.ShardHttp
import ac.shard.platform.Lifecycle
import ac.shard.platform.scheduler.TaskHandle
import ac.shard.scheduler.SchedulerService
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

internal fun cooldownFor(
  previous: AiConnection?,
  next: AiConnection,
  current: ApiCooldown?,
  clock: () -> Long,
): ApiCooldown {
  val sameEndpoint = previous?.url == next.url && previous.key == next.key
  if (current == null || !sameEndpoint) return ApiCooldown(next.backoff, clock)
  if (previous.backoff != next.backoff) current.retune(next.backoff)
  return current
}

class AIServerProvider(
  private val logger: Logger,
  private val http: ShardHttp,
  private val configManager: ConfigManager,
  private val scheduler: SchedulerService,
  private val credentialsStore: CredentialsStore,
) : Lifecycle {
  private val origin = System.nanoTime()
  @Volatile private var apiCooldown: ApiCooldown? = null
  @Volatile private var stream: StreamTransport? = null
  @Volatile private var streamTimer: TaskHandle? = null
  private val probes = ProbeBackoff(::nowMs)
  private val verdictThread: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "Shard-Verdicts").apply { isDaemon = true }
  }

  private val sendThread: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "Shard-Stream").apply { isDaemon = true }
  }

  val verdicts: Executor
    get() = verdictThread

  val isEnabled: Boolean
    get() = stream != null

  @Volatile private var connection: AiConnection? = null

  override fun start() = reload()

  fun reload() {
    val next = configManager.settings.ai
    if (next == connection && stream != null) return
    shutdown()
    apiCooldown = cooldownFor(connection, next, apiCooldown, ::nowMs)
    connection = next
    stream = buildServer(next)?.let { buildStream(it, next) }
  }

  fun shutdown() {
    streamTimer?.cancel()
    streamTimer = null
    stream?.stop()
    stream = null
  }

  fun streamTransport(): StreamTransport? = stream

  private fun buildStream(server: AIServer, ai: AiConnection): StreamTransport {
    val cooldown = apiCooldown!!
    val transport =
      StreamTransport(
        sender = server::sendStream,
        profiles = { configManager.streamProfile },
        profileFor = configManager.profiles::recent,
        onProfile = { node, crc ->
          val profiles = configManager.profiles
          when {
            profiles.accept(node, crc) -> cooldown.profileAccepted()
            profiles.isRejected(node) -> cooldown.holdForRejectedProfile()
          }
        },
        onManifestRequired = { probe() },
        backingOff = cooldown::isWaiting,
        clock = ::nowMs,
        maxBatch = ai.batchMaxSize.coerceIn(1, AIServer.BATCH_MAX_ITEMS),
        errors = StreamErrorLog({ level, message -> logger.log(level, message) }, ::nowMs),
        sendOn = sendThread,
      )
    probes.reset()
    val period = ai.batchMaxDelayMs.coerceAtLeast(MIN_STREAM_PERIOD_MS)
    streamTimer =
      scheduler.runTimerAsync(
        {
          bootstrap(transport, cooldown)
          sendThread.execute(transport::drain)
        },
        period,
        period,
      )
    return transport
  }

  private fun bootstrap(transport: StreamTransport, cooldown: ApiCooldown) {
    if (configManager.streamProfile != null) {
      probes.settled()
      return
    }
    if (!cooldown.isWaiting()) probe(transport)
  }

  fun probe(transport: StreamTransport? = stream) {
    val target = transport ?: return
    if (!probes.tryStart()) return
    target.probe(ChunkWriter.probe()).whenComplete { _, _ ->
      probes.finished(configManager.streamProfile != null)
    }
  }

  private fun nowMs(): Long = (System.nanoTime() - origin) / NANOS_PER_MILLI

  override fun stop() {
    shutdown()
    sendThread.shutdownNow()
    verdictThread.shutdownNow()
  }

  private fun buildServer(ai: AiConnection): AIServer? {
    val url = ai.url
    val key = ai.key
    val state =
      when {
        !ai.enabled -> ServerState.DISABLED
        url.isBlank() || url == LEGACY_PLACEHOLDER_URL || key == "API-KEY" ->
          ServerState.NOT_CONFIGURED
        else -> ServerState.READY
      }
    return when (state) {
      ServerState.DISABLED -> {
        logger.info("[AI] Detection is disabled.")
        null
      }
      ServerState.NOT_CONFIGURED -> {
        logger.warning("[AI] AI is enabled but not configured.")
        null
      }
      ServerState.READY ->
        runCatching {
            AIServer(
                http,
                url,
                key,
                apiCooldown!!,
                credentialsStore.instanceId(),
                ai.gzip,
              )
              .also { it.validateHeaders() }
          }
          .onSuccess { logger.info("[AI] Detection is ready.") }
          .onFailure {
            logger.severe(
              "[AI] AI is disabled: ai.server or the API key is invalid (${it.message})"
            )
          }
          .getOrNull()
    }
  }

  private enum class ServerState {
    DISABLED,
    NOT_CONFIGURED,
    READY,
  }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000L
    const val MIN_STREAM_PERIOD_MS = 5L
    // Old config.yml placeholder URL - treat as not-configured.
    const val LEGACY_PLACEHOLDER_URL = "https://url/v1/inference"
  }
}
