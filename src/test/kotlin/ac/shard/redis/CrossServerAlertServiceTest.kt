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
package ac.shard.redis

import ac.shard.alert.AlertManager
import ac.shard.alert.AlertType
import ac.shard.config.ConfigManager
import ac.shard.config.ConfigView
import ac.shard.config.LocaleManager
import ac.shard.platform.scheduler.TaskHandle
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.MessageUtil
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.util.logging.Logger
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class CrossServerAlertServiceTest {
  private val gson = GsonComponentSerializer.gson()
  private val mapper = ObjectMapper()

  private val scheduler = mockk<SchedulerService>()

  @BeforeEach
  fun setUp() {
    val localeManager = mockk<LocaleManager>(relaxed = true)
    every { localeManager.getRawMessage(any()) } returns "[<server>]"
    MessageUtil.init(
      localeManager,
      mockk<BukkitAudiences>(relaxed = true),
      Logger.getLogger("test"),
    )
    every { scheduler.runSync(any()) } answers
      {
        firstArg<Runnable>().run()
        mockk<TaskHandle>(relaxed = true)
      }
  }

  @AfterEach
  fun tearDown() {
    MessageUtil.init(
      mockk<LocaleManager>(relaxed = true),
      mockk<BukkitAudiences>(relaxed = true),
      Logger.getLogger("test"),
    )
  }

  @Test
  fun `publishes only the enabled alert types`() {
    val redis = mockk<RedisManager>(relaxed = true)
    every { redis.isAvailable } returns true
    val alerts = mockk<AlertManager>(relaxed = true)
    val service =
      service(
        """
        network:
          enabled: true
          name: "Lobby"
          channel: "test:alerts"
          share:
            alerts: true
            suspicious: false
        """
          .trimIndent(),
        redis,
        alerts,
      )

    service.start()
    service.publish(AlertType.REGULAR, Component.text("a"))
    service.publish(AlertType.SUSPICIOUS, Component.text("b"))
    service.publish(AlertType.BRAND, Component.text("c"))

    verify(exactly = 1) { redis.publishAsync("test:alerts", any()) }
    verify(exactly = 1) { alerts.crossServerPublisher = any() }
    verify(exactly = 1) { redis.subscribe("test:alerts", any()) }
  }

  @Test
  fun `does nothing when cross-server is disabled`() {
    val redis = mockk<RedisManager>(relaxed = true)
    val alerts = mockk<AlertManager>(relaxed = true)
    val service = service("network:\n  enabled: false\n", redis, alerts)

    service.start()
    service.publish(AlertType.REGULAR, Component.text("a"))

    verify(exactly = 0) { redis.start() }
    verify(exactly = 0) { redis.subscribe(any(), any()) }
    verify(exactly = 0) { redis.publishAsync(any(), any()) }
    verify(exactly = 0) { alerts.crossServerPublisher = any() }
  }

  @Test
  fun `stays local when redis is unavailable`() {
    val redis = mockk<RedisManager>(relaxed = true)
    every { redis.isAvailable } returns false
    val alerts = mockk<AlertManager>(relaxed = true)
    val service = service("network:\n  enabled: true\n", redis, alerts)

    service.start()
    service.publish(AlertType.REGULAR, Component.text("a"))

    verify(exactly = 1) { redis.start() }
    verify(exactly = 0) { redis.subscribe(any(), any()) }
    verify(exactly = 0) { redis.publishAsync(any(), any()) }
    verify(exactly = 0) { alerts.crossServerPublisher = any() }
  }

  @Test
  fun `delivers foreign alerts and ignores its own`() {
    val redis = mockk<RedisManager>(relaxed = true)
    every { redis.isAvailable } returns true
    val incoming = slot<(String) -> Unit>()
    every { redis.subscribe(any(), capture(incoming)) } just runs
    val published = slot<String>()
    every { redis.publishAsync(any(), capture(published)) } just runs
    val alerts = mockk<AlertManager>(relaxed = true)
    val service =
      service(
        """
        network:
          enabled: true
          name: "Lobby"
          channel: "test:alerts"
          share:
            alerts: true
            suspicious: true
        """
          .trimIndent(),
        redis,
        alerts,
      )
    service.start()

    service.publish(AlertType.REGULAR, Component.text("local"))
    incoming.captured.invoke(published.captured)
    verify(exactly = 0) { alerts.deliver(any(), any()) }

    val foreign =
      mapper.writeValueAsString(
        CrossServerAlert("other-origin", "PvP", "REGULAR", gson.serialize(Component.text("remote")))
      )
    incoming.captured.invoke(foreign)
    verify(exactly = 1) { scheduler.runSync(any()) }
    verify(exactly = 1) { alerts.deliver(any(), AlertType.REGULAR) }
  }

  @Test
  fun `strips click events from foreign alerts`() {
    val redis = mockk<RedisManager>(relaxed = true)
    every { redis.isAvailable } returns true
    val incoming = slot<(String) -> Unit>()
    every { redis.subscribe(any(), capture(incoming)) } just runs
    val alerts = mockk<AlertManager>(relaxed = true)
    val delivered = slot<Component>()
    every { alerts.deliver(capture(delivered), any()) } just runs
    val service = service("network:\n  enabled: true\n", redis, alerts)
    service.start()

    val hostile =
      Component.text("click me")
        .clickEvent(ClickEvent.runCommand("/op Evil"))
        .append(Component.text("child").clickEvent(ClickEvent.runCommand("/stop")))
    incoming.captured.invoke(
      mapper.writeValueAsString(
        CrossServerAlert("other-origin", "PvP", "REGULAR", gson.serialize(hostile))
      )
    )

    fun hasClick(component: Component): Boolean =
      component.clickEvent() != null || component.children().any(::hasClick)
    kotlin.test.assertFalse(hasClick(delivered.captured))
  }

  @Test
  fun `ignores malformed and unknown-type messages`() {
    val redis = mockk<RedisManager>(relaxed = true)
    every { redis.isAvailable } returns true
    val incoming = slot<(String) -> Unit>()
    every { redis.subscribe(any(), capture(incoming)) } just runs
    val alerts = mockk<AlertManager>(relaxed = true)
    val service = service("network:\n  enabled: true\n", redis, alerts)
    service.start()

    incoming.captured.invoke("not even json")
    incoming.captured.invoke(
      mapper.writeValueAsString(
        CrossServerAlert("o", "PvP", "NONSENSE", gson.serialize(Component.text("x")))
      )
    )

    verify(exactly = 0) { alerts.deliver(any(), any()) }
  }

  private fun service(
    yaml: String,
    redis: RedisManager,
    alerts: AlertManager,
  ): CrossServerAlertService {
    val configManager = mockk<ConfigManager>()
    every { configManager.config } returns configView(yaml)
    return CrossServerAlertService(
      configManager,
      redis,
      alerts,
      scheduler,
      Logger.getLogger("cross-server-test"),
    )
  }

  private fun configView(yaml: String): ConfigView {
    val loader = YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build()
    return ConfigView(loader.load())
  }
}
