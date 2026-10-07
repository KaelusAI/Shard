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
package ac.shard.network

import ac.shard.config.ConfigManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisClientConfig
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.RedisClient
import redis.clients.jedis.params.ScanParams

class RedisManager(private val configManager: ConfigManager, private val logger: Logger) {
  private val lock = Any()
  private var generation = 0
  private var attempted = false
  @Volatile private var session: Session? = null

  @Volatile
  var isAvailable: Boolean = false
    private set

  fun start() {
    val generation =
      synchronized(lock) {
        if (attempted) return
        attempted = true
        this.generation
      }
    val config = configManager.config
    if (!config.getBoolean("redis.enabled", false)) return

    val host = config.getString("redis.host", DEFAULT_HOST)
    val port = config.getInt("redis.port", DEFAULT_PORT)
    val database = config.getInt("redis.database", DEFAULT_DATABASE)
    val timeoutMillis =
      (config
          .getLong("redis.timeout-seconds", DEFAULT_TIMEOUT_SECONDS)
          .coerceIn(1L, MAX_TIMEOUT_SECONDS) * MILLIS_PER_SECOND)
        .toInt()
    val password = config.getString("redis.password", "")

    val clientConfig =
      DefaultJedisClientConfig.builder()
        .database(database)
        .ssl(config.getBoolean("redis.ssl", false))
        .connectionTimeoutMillis(timeoutMillis)
        .socketTimeoutMillis(timeoutMillis)
        .apply { if (password.isNotEmpty()) password(password) }
        .build()
    val endpoint = HostAndPort(host, port)
    val client = RedisClient.builder().hostAndPort(endpoint).clientConfig(clientConfig).build()
    runCatching { client.ping() }
      .onSuccess {
        val candidate = Session(client, endpoint, clientConfig)
        if (adopt(generation, candidate)) {
          logger.info("[Redis] Connected to $host:$port (database $database).")
        } else {
          candidate.close()
        }
      }
      .onFailure { error ->
        runCatching { client.close() }
        logger.log(
          Level.WARNING,
          "[Redis] Could not connect to $host:$port, network features are disabled.",
          error,
        )
      }
  }

  private fun adopt(generation: Int, candidate: Session): Boolean =
    synchronized(lock) {
      if (generation != this.generation) return false
      session = candidate
      isAvailable = true
      true
    }

  fun publishAsync(channel: String, message: String) {
    session?.write("Publish to $channel") { it.publish(channel, message) }
  }

  fun setWithTtl(key: String, value: String, ttlSeconds: Long) {
    session?.write("Set $key") { it.setex(key, ttlSeconds, value) }
  }

  @Suppress("SpreadOperator")
  fun scanValues(pattern: String): List<String> {
    val client = session?.client ?: return emptyList()
    val params = ScanParams().match(pattern).count(SCAN_BATCH)
    val keys = ArrayList<String>()
    var cursor = ScanParams.SCAN_POINTER_START
    do {
      val page = client.scan(cursor, params)
      keys.addAll(page.result)
      cursor = page.cursor
    } while (!page.isCompleteIteration)
    return if (keys.isEmpty()) emptyList() else client.mget(*keys.toTypedArray()).filterNotNull()
  }

  fun subscribe(channel: String, onMessage: (String) -> Unit) {
    session?.subscribe(channel, onMessage)
  }

  fun shutdown() {
    val closing =
      synchronized(lock) {
        generation++
        isAvailable = false
        attempted = false
        val current = session
        session = null
        current
      }
    closing?.close()
  }

  private inner class Session(
    val client: RedisClient,
    private val endpoint: HostAndPort,
    clientConfig: JedisClientConfig,
  ) {
    private val writer =
      ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(MAX_PENDING_WRITES),
        { task -> Thread(task, "Shard-Redis").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
      )
    private val subscriberConfig =
      DefaultJedisClientConfig.builder().from(clientConfig).autoNegotiateProtocol(false).build()
    private val handlers = ConcurrentHashMap<String, (String) -> Unit>()
    private val confirmations = ConcurrentHashMap<String, CountDownLatch>()
    private var listener: Thread? = null
    private var listening: Jedis? = null
    private var listeningTo: Set<String> = emptySet()
    private var resubscribing = false
    @Volatile private var closed = false
    @Volatile private var disconnected = false

    fun write(what: String, command: (RedisClient) -> Unit) {
      writer.execute {
        runCatching { command(client) }
          .onFailure { error -> logger.log(Level.FINE, "[Redis] $what failed.", error) }
      }
    }

    fun subscribe(channel: String, onMessage: (String) -> Unit) {
      val confirmed = CountDownLatch(1)
      confirmations[channel] = confirmed
      handlers[channel] = onMessage
      synchronized(this) {
        if (closed) return
        val active = listening
        if (active != null && channel !in listeningTo) {
          resubscribing = true
          active.disconnect()
        }
        if (listener == null) {
          listener = Thread(::listen, "Shard-Redis-Subscriber").apply { isDaemon = true }
          listener?.start()
        }
      }
      if (!confirmed.await(SUBSCRIBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warning("[Redis] Subscription to $channel is not confirmed yet, retrying.")
      }
    }

    private fun listen() {
      var backoff = MIN_BACKOFF_MILLIS
      while (!closed) {
        val pubSub = Listener()
        val failure = listenOnce(pubSub)
        if (!closed && !wasResubscribing()) {
          if (pubSub.confirmed) backoff = MIN_BACKOFF_MILLIS
          reportLost(failure)
          if (!pause(backoff)) break
          backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
      }
    }

    private fun reportLost(failure: Throwable?) {
      if (disconnected) return
      disconnected = true
      val cause = failure?.message?.let { ": $it" }.orEmpty()
      logger.warning("[Redis] Lost the subscription, reconnecting$cause")
    }

    @Suppress("SpreadOperator")
    private fun listenOnce(pubSub: Listener): Throwable? =
      runCatching {
          Jedis(endpoint, subscriberConfig).use { jedis ->
            val channels =
              synchronized(this) {
                listening = jedis
                listeningTo = handlers.keys.toSet()
                listeningTo.takeUnless { closed }
              }
            if (channels != null) jedis.subscribe(pubSub, *channels.toTypedArray())
          }
        }
        .exceptionOrNull()

    private fun wasResubscribing(): Boolean =
      synchronized(this) {
        listening = null
        resubscribing.also { resubscribing = false }
      }

    private fun pause(millis: Long): Boolean =
      try {
        Thread.sleep(millis)
        true
      } catch (_: InterruptedException) {
        false
      }

    fun close() {
      closed = true
      synchronized(this) {
        listening?.disconnect()
        listener?.interrupt()
      }
      writer.shutdownNow()
      runCatching { client.close() }
    }

    private inner class Listener : JedisPubSub() {
      @Volatile var confirmed = false

      override fun onSubscribe(channel: String, subscribedChannels: Int) {
        confirmed = true
        confirmations.remove(channel)?.countDown()
        if (disconnected) {
          disconnected = false
          logger.info("[Redis] Subscription restored.")
        }
      }

      override fun onMessage(channel: String, message: String) {
        val handler = handlers[channel] ?: return
        runCatching { handler(message) }
          .onFailure { error ->
            logger.log(Level.FINE, "[Redis] Handler for $channel failed.", error)
          }
      }
    }
  }

  private companion object {
    const val DEFAULT_HOST = "localhost"
    const val DEFAULT_PORT = 6379
    const val DEFAULT_DATABASE = 0
    const val DEFAULT_TIMEOUT_SECONDS = 10L
    const val MAX_TIMEOUT_SECONDS = 60L
    const val MILLIS_PER_SECOND = 1000L
    const val SCAN_BATCH = 256
    const val MAX_PENDING_WRITES = 1024
    const val SUBSCRIBE_TIMEOUT_SECONDS = 5L
    const val MIN_BACKOFF_MILLIS = 500L
    const val MAX_BACKOFF_MILLIS = 30_000L
  }
}
