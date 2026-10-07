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

import ac.shard.alert.AlertType
import ac.shard.alert.NetworkPublisher
import ac.shard.config.ConfigManager
import ac.shard.http.Json
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.Message
import ac.shard.utils.Messages
import ac.shard.utils.MiniText
import java.util.EnumSet
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer

class NetworkAlertService(
  private val messages: Messages,
  private val configManager: ConfigManager,
  private val redisManager: RedisManager,
  private val scheduler: SchedulerService,
  private val logger: Logger,
) : NetworkPublisher {
  private val origin: String = UUID.randomUUID().toString()
  private val mapper = Json.lenient
  private val componentSerializer = GsonComponentSerializer.gson()

  @Volatile private var remote: ((Component, AlertType, String) -> Unit)? = null
  @Volatile private var enabled = false
  @Volatile private var mirroredTypes: Set<AlertType> = emptySet()
  @Volatile private var serverName = DEFAULT_SERVER_NAME
  @Volatile private var channel = DEFAULT_CHANNEL

  val isEnabled: Boolean
    get() = enabled

  override val name: String
    get() = serverName

  fun start() {
    val config = configManager.config
    if (!config.getBoolean("network.enabled", false)) return

    serverName = config.getString("network.name", DEFAULT_SERVER_NAME)
    channel = config.getString("network.channel", DEFAULT_CHANNEL)
    val types = EnumSet.noneOf(AlertType::class.java)
    if (config.getBoolean("network.share.alerts", true)) types.add(AlertType.REGULAR)
    if (config.getBoolean("network.share.suspicious", true)) types.add(AlertType.SUSPICIOUS)
    mirroredTypes = types

    redisManager.start()
    if (!redisManager.isAvailable) {
      logger.warning(
        "[Network] network.enabled is true but Redis is unavailable; alerts stay local."
      )
      return
    }

    redisManager.subscribe(channel, ::onMessage)
    enabled = true
    logger.info(
      "[Network] Mirroring ${mirroredTypes.joinToString(", ")} alerts as " +
        "\"$serverName\" on channel \"$channel\"."
    )
  }

  override fun onRemote(listener: (Component, AlertType, String) -> Unit) {
    remote = listener
  }

  override fun publish(type: AlertType, component: Component) {
    if (!enabled || !mirrors(type)) return
    val payload =
      runCatching {
          val alert =
            NetworkAlert(
              origin,
              serverName,
              type.name,
              componentSerializer.serialize(component),
            )
          mapper.writeValueAsString(alert)
        }
        .getOrElse { error ->
          logger.log(Level.FINE, "[Network] Failed to serialize alert.", error)
          return
        }
    redisManager.publishAsync(channel, payload)
  }

  private fun onMessage(raw: String) {
    runCatching { handleMessage(raw) }
      .onFailure { error ->
        logger.log(Level.FINE, "[Network] Failed to handle incoming alert.", error)
      }
  }

  @Suppress("ReturnCount")
  private fun handleMessage(raw: String) {
    if (!enabled) return
    val alert = mapper.readValue(raw, NetworkAlert::class.java)
    if (alert.origin == origin) return
    val type = runCatching { AlertType.valueOf(alert.type) }.getOrNull() ?: return
    if (!mirrors(type)) return
    val body = stripClickEvents(componentSerializer.deserialize(alert.component))
    val prefix =
      messages.getMessage(Message.NETWORK_ALERT_PREFIX, "server", MiniText.escape(alert.server))
    val message = prefix.append(Component.space()).append(body)
    val listener = remote ?: return
    scheduler.runSync { listener(message, type, alert.server) }
  }

  private fun mirrors(type: AlertType): Boolean = type == AlertType.CUSTOM || type in mirroredTypes

  private fun stripClickEvents(component: Component): Component {
    val stripped = component.clickEvent(null)
    val children = component.children()
    if (children.isEmpty()) {
      return stripped
    }
    return stripped.children(children.map(::stripClickEvents))
  }

  fun shutdown() {
    enabled = false
  }

  private companion object {
    const val DEFAULT_SERVER_NAME = "server-1"
    const val DEFAULT_CHANNEL = "shard:alerts"
  }
}
