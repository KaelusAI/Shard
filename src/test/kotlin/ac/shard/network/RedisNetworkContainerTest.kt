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
import ac.shard.config.ConfigView
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Tag("container")
@Testcontainers(disabledWithoutDocker = true)
class RedisNetworkContainerTest {

  @Test
  fun `a message published on one manager is received by another over redis`() {
    GenericContainer(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(REDIS_PORT).use {
      container ->
      container.start()
      val publisher = manager(container)
      val subscriber = manager(container)
      try {
        publisher.start()
        subscriber.start()
        assertTrue(publisher.isAvailable)
        assertTrue(subscriber.isAvailable)

        val received = LinkedBlockingQueue<String>()
        subscriber.subscribe(CHANNEL) { message -> received.add(message) }
        publisher.publishAsync(CHANNEL, PAYLOAD)

        assertEquals(PAYLOAD, received.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      } finally {
        publisher.shutdown()
        subscriber.shutdown()
      }
    }
  }

  @Test
  fun `a subscriber reconnects after redis drops its connection`() {
    GenericContainer(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(REDIS_PORT).use {
      container ->
      container.start()
      val publisher = manager(container)
      val subscriber = manager(container)
      try {
        publisher.start()
        subscriber.start()
        val received = LinkedBlockingQueue<String>()
        subscriber.subscribe(CHANNEL) { message -> received.add(message) }

        val killed = container.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "pubsub")
        assertEquals("1", killed.stdout.trim())

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_TIMEOUT_SECONDS)
        var delivered: String? = null
        while (delivered == null && System.nanoTime() < deadline) {
          publisher.publishAsync(CHANNEL, PAYLOAD)
          delivered = received.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
        }
        assertEquals(PAYLOAD, delivered)
      } finally {
        publisher.shutdown()
        subscriber.shutdown()
      }
    }
  }

  private fun manager(container: GenericContainer<*>): RedisManager {
    val yaml =
      """
      redis:
        enabled: true
        host: "${container.host}"
        port: ${container.firstMappedPort}
      """
        .trimIndent()
    val loader = YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build()
    val configManager = mockk<ConfigManager>()
    every { configManager.config } returns ConfigView(loader.load())
    return RedisManager(configManager, Logger.getLogger("redis-it"))
  }

  private companion object {
    const val REDIS_IMAGE = "redis:7-alpine"
    const val REDIS_PORT = 6379
    const val CHANNEL = "shard:it-alerts"
    const val PAYLOAD = "network-payload"
    const val AWAIT_TIMEOUT_SECONDS = 5L
    const val POLL_MILLIS = 200L
  }
}
