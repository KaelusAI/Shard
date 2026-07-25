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
package ac.shard.command.commands.info

import ac.shard.Shard
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.command.CommandRegister
import ac.shard.command.ShardCommand
import ac.shard.command.requirements.PlayerSenderRequirement
import ac.shard.config.ConfigManager
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorSettingsService
import ac.shard.monitor.core.MonitorTheme
import ac.shard.platform.scheduler.TaskHandle
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.PlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister
import org.incendo.cloud.kotlin.extension.suggestionProvider
import org.incendo.cloud.parser.standard.StringParser
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider

class MonitorCommand(
  private val playerDataManager: PlayerDataManager,
  private val settingsService: MonitorSettingsService,
  private val configManager: ConfigManager,
  plugin: Shard,
  private val adventure: BukkitAudiences,
  private val scheduler: SchedulerService,
) : ShardCommand, Listener {
  private val activeSessions = ConcurrentHashMap<UUID, MonitorSession>()

  init {
    plugin.server.pluginManager.registerEvents(this, plugin)
  }

  override fun register(manager: CommandManager<Sender>) {
    val modeSuggestions =
      SuggestionProvider.suggesting<Sender>(
        MonitorMode.entries.map { Suggestion.suggestion(it.name.lowercase(Locale.ROOT)) }
      )
    val themeSuggestions =
      SuggestionProvider.suggesting<Sender>(
        MonitorTheme.entries.map { Suggestion.suggestion(it.name.lowercase(Locale.ROOT)) }
      )
    val toggleSuggestions =
      SuggestionProvider.suggesting<Sender>(listOf("on", "off").map { Suggestion.suggestion(it) })
    val nameSuggestions =
      SuggestionProvider.suggesting<Sender>(
        MonitorNameMode.entries.map { Suggestion.suggestion(it.name.lowercase(Locale.ROOT)) }
      )

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.self")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .handler(this@MonitorCommand::toggleMonitorSelf)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.self")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .required("target", PlayerParser.playerParser())
        .handler(this@MonitorCommand::toggleMonitor)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.self")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .literal("stop")
        .handler(this@MonitorCommand::stopMonitor)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.self")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .literal("reset")
        .handler(this@MonitorCommand::resetSettings)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.list")
        .literal("list")
        .handler(this@MonitorCommand::listSessions)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.self")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .literal("name")
        .required("mode", StringParser.stringParser()) { suggestionProvider = nameSuggestions }
        .handler(this@MonitorCommand::setNameMode)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.self")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .literal("mode")
        .required("mode", StringParser.stringParser()) { suggestionProvider = modeSuggestions }
        .handler(this@MonitorCommand::setMode)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.self")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .literal("ping")
        .required("state", StringParser.stringParser()) { suggestionProvider = toggleSuggestions }
        .handler { ctx -> setFlag(ctx, SettingKey.PING) }
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.self")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .literal("dmg")
        .required("state", StringParser.stringParser()) { suggestionProvider = toggleSuggestions }
        .handler { ctx -> setFlag(ctx, SettingKey.DMG) }
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.self")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .literal("trend")
        .required("state", StringParser.stringParser()) { suggestionProvider = toggleSuggestions }
        .handler { ctx -> setFlag(ctx, SettingKey.TREND) }
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("monitor")
        .permission("shard.monitor.self")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .literal("theme")
        .required("theme", StringParser.stringParser()) { suggestionProvider = themeSuggestions }
        .handler(this@MonitorCommand::setTheme)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("prob").permission("shard.monitor.self").handler(this@MonitorCommand::deprecatedProb)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("prob")
        .permission("shard.monitor.self")
        .required("target", PlayerParser.playerParser())
        .handler(this@MonitorCommand::deprecatedProb)
    }
  }

  @EventHandler
  fun onPlayerQuit(event: PlayerQuitEvent) {
    val player = event.player
    val uuid = player.uniqueId

    if (activeSessions.containsKey(uuid)) {
      stop(player)
    }

    var viewerUuid: UUID? = null
    for ((key, value) in activeSessions.entries) {
      if (value.targetUuid == uuid) {
        viewerUuid = key
        break
      }
    }

    if (viewerUuid != null) {
      val viewer = Bukkit.getPlayer(viewerUuid)
      if (viewer != null) {
        stop(viewer)
        MessageUtil.sendMessage(viewer, Message.MONITOR_DISABLED, "player", player.name)
      } else {
        activeSessions.remove(viewerUuid)
      }
    }
  }

  private fun toggleMonitorSelf(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    toggleMonitorFor(player, player)
  }

  private fun deprecatedProb(context: CommandContext<Sender>) {
    MessageUtil.sendMessage(context.sender().nativeSender, Message.MONITOR_PROB_DEPRECATED)
  }

  private fun stopMonitor(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val session = activeSessions[player.uniqueId]
    if (session == null) {
      MessageUtil.sendMessage(sender.nativeSender, Message.MONITOR_NOT_ACTIVE)
      return
    }
    val targetName = Bukkit.getPlayer(session.targetUuid)?.name ?: session.targetUuid.toString()
    stop(player)
    MessageUtil.sendMessage(sender.nativeSender, Message.MONITOR_DISABLED, "player", targetName)
  }

  private fun resetSettings(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    settingsService.updateSettings(player.uniqueId, settingsService.defaultSettings())
    MessageUtil.sendMessage(sender.nativeSender, Message.MONITOR_RESET)
  }

  private fun listSessions(context: CommandContext<Sender>) {
    val sender = context.sender()
    val nativeSender = sender.nativeSender
    if (activeSessions.isEmpty()) {
      MessageUtil.sendMessage(nativeSender, Message.MONITOR_LIST_EMPTY)
      return
    }
    MessageUtil.sendMessage(
      nativeSender,
      Message.MONITOR_LIST_HEADER,
      "count",
      activeSessions.size.toString(),
    )
    for ((viewerUuid, session) in activeSessions) {
      val viewerName = Bukkit.getPlayer(viewerUuid)?.name ?: viewerUuid.toString()
      val targetName = Bukkit.getPlayer(session.targetUuid)?.name ?: session.targetUuid.toString()
      MessageUtil.sendMessage(
        nativeSender,
        Message.MONITOR_LIST_ENTRY,
        "viewer",
        viewerName,
        "target",
        targetName,
      )
    }
  }

  private fun setNameMode(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val rawMode: String = context["mode"]
    val mode = MonitorNameMode.entries.firstOrNull { it.name.equals(rawMode, ignoreCase = true) }
    if (mode == null) {
      MessageUtil.sendMessage(
        sender.nativeSender,
        Message.MONITOR_INVALID_SETTING,
        "setting",
        "name",
        "options",
        MonitorNameMode.entries.joinToString("/") { it.name.lowercase(Locale.ROOT) },
      )
      return
    }
    updateSettings(player) { settings -> settings.showName = mode }
    MessageUtil.sendMessage(
      sender.nativeSender,
      Message.MONITOR_SETTING_UPDATED,
      "setting",
      "name",
      "value",
      mode.name.lowercase(Locale.ROOT),
    )
  }

  private fun toggleMonitor(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val target: Player = context["target"]

    if (player.uniqueId != target.uniqueId && !player.hasPermission("shard.monitor")) {
      MessageUtil.sendMessage(sender.nativeSender, Message.MONITOR_NO_PERMISSION_OTHER)
      return
    }

    toggleMonitorFor(player, target)
  }

  private fun toggleMonitorFor(player: Player, target: Player) {
    val session = activeSessions[player.uniqueId]

    if (session != null && session.targetUuid == target.uniqueId) {
      stop(player)
      MessageUtil.sendMessage(player, Message.MONITOR_DISABLED, "player", target.name)
      return
    }

    if (session != null) {
      stop(player)
    }

    start(player, target)
    MessageUtil.sendMessage(player, Message.MONITOR_ENABLED, "player", target.name)
  }

  private fun setMode(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val rawMode: String = context["mode"]
    val mode = MonitorMode.fromConfig(rawMode)
    if (
      !rawMode.equals("compact", ignoreCase = true) && !rawMode.equals("full", ignoreCase = true)
    ) {
      MessageUtil.sendMessage(
        sender.nativeSender,
        Message.MONITOR_INVALID_SETTING,
        "setting",
        "mode",
        "options",
        "compact/full",
      )
      return
    }

    updateSettings(player) { settings -> settings.mode = mode }
    MessageUtil.sendMessage(
      sender.nativeSender,
      Message.MONITOR_SETTING_UPDATED,
      "setting",
      "mode",
      "value",
      mode.name.lowercase(Locale.ROOT),
    )
  }

  private fun setTheme(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val rawTheme: String = context["theme"]
    if (
      !rawTheme.equals("calm", ignoreCase = true) &&
        !rawTheme.equals("vivid", ignoreCase = true) &&
        !rawTheme.equals("minimal", ignoreCase = true)
    ) {
      MessageUtil.sendMessage(
        sender.nativeSender,
        Message.MONITOR_INVALID_SETTING,
        "setting",
        "theme",
        "options",
        "calm/vivid/minimal",
      )
      return
    }

    val theme = MonitorTheme.fromConfig(rawTheme)
    updateSettings(player) { settings -> settings.theme = theme }
    MessageUtil.sendMessage(
      sender.nativeSender,
      Message.MONITOR_SETTING_UPDATED,
      "setting",
      "theme",
      "value",
      theme.name.lowercase(Locale.ROOT),
    )
  }

  private fun setFlag(context: CommandContext<Sender>, key: SettingKey) {
    val sender = context.sender()
    val player = sender.player ?: return
    val rawState: String = context["state"]
    val enabled = parseToggle(rawState)
    if (enabled == null) {
      MessageUtil.sendMessage(
        sender.nativeSender,
        Message.MONITOR_INVALID_SETTING,
        "setting",
        key.label,
        "options",
        "on/off",
      )
      return
    }

    updateSettings(player) { settings ->
      when (key) {
        SettingKey.PING -> settings.showPing = enabled
        SettingKey.DMG -> settings.showDmg = enabled
        SettingKey.TREND -> settings.showTrend = enabled
      }
    }

    MessageUtil.sendMessage(
      sender.nativeSender,
      Message.MONITOR_SETTING_UPDATED,
      "setting",
      key.label,
      "value",
      if (enabled) "on" else "off",
    )
  }

  private fun updateSettings(player: Player, updater: (MonitorSettings) -> Unit) {
    val settings = settingsService.getSettings(player.uniqueId)
    updater(settings)
    settingsService.updateSettings(player.uniqueId, settings)
  }

  private fun parseToggle(raw: String?): Boolean? {
    if (raw == null) return null
    if (raw.equals("on", ignoreCase = true) || raw.equals("true", ignoreCase = true)) return true
    if (raw.equals("off", ignoreCase = true) || raw.equals("false", ignoreCase = true)) return false
    return null
  }

  private fun start(viewer: Player, target: Player) {
    val viewerId = viewer.uniqueId
    val targetId = target.uniqueId

    val updatePeriodTicks = configManager.monitorConfig.getLong("update", 2L).coerceAtLeast(1L)
    val keepAliveTicks =
      configManager.monitorConfig
        .getLong("behavior.keepalive-ticks", DEFAULT_KEEP_ALIVE_TICKS)
        .coerceAtLeast(1L)
    val keepAliveCycles =
      ((keepAliveTicks + updatePeriodTicks - 1L) / updatePeriodTicks).coerceAtLeast(1L).toInt()

    val targetName = target.name
    val newSession =
      MonitorSession(
        targetUuid = targetId,
        keepAliveCycles = keepAliveCycles,
        noDataComponent = MessageUtil.getMessage(Message.MONITOR_NO_DATA, "player", targetName),
        noAiCheckComponent =
          MessageUtil.getMessage(Message.MONITOR_NO_AICHECK, "player", targetName),
      )
    activeSessions[viewerId] = newSession

    val task =
      scheduler.runTimer(
        viewer,
        Runnable {
          val onlineViewer = Bukkit.getPlayer(viewerId)
          val onlineTarget = Bukkit.getPlayer(targetId)

          if (
            onlineViewer == null ||
              !onlineViewer.isOnline ||
              onlineTarget == null ||
              !onlineTarget.isOnline
          ) {
            if (onlineViewer != null) stop(onlineViewer)
            return@Runnable
          }

          val shardTarget = playerDataManager.getPlayer(onlineTarget)
          if (shardTarget == null) {
            sendActionBar(onlineViewer, newSession.noDataComponent)
            return@Runnable
          }

          val aiCheck: AiCheck? = shardTarget.checkManager.getCheck(AiCheck::class.java)
          if (aiCheck == null) {
            sendActionBar(onlineViewer, newSession.noAiCheckComponent)
            return@Runnable
          }

          sendActionBar(onlineViewer, onlineTarget, shardTarget, aiCheck, newSession)
        },
        1L,
        updatePeriodTicks,
      )

    newSession.task = task
  }

  private fun stop(viewer: Player) {
    val session = activeSessions.remove(viewer.uniqueId)
    if (session != null && session.task != null) {
      session.task!!.cancel()
      sendActionBar(viewer, Component.empty())
    }
  }

  private fun sendActionBar(
    viewer: Player,
    target: Player,
    shardTarget: ShardPlayer,
    aiCheck: AiCheck,
    session: MonitorSession,
  ) {
    val probability = aiCheck.lastProbability
    val buffer = aiCheck.buffer
    val ping = target.ping
    val dmgMultiplier = shardTarget.combat.damageMultiplier
    val settings = settingsService.getSettings(viewer.uniqueId)

    val trendDecimals = configManager.monitorConfig.getInt("format.trend.decimals", 2)
    val thresholdConfig = configManager.monitorConfig.getDouble("format.trend.threshold", 0.0)
    val trendThreshold =
      if (thresholdConfig > 0.0) {
        thresholdConfig
      } else {
        AUTO_TREND_THRESHOLD_SCALE * 10.0.pow(-trendDecimals.toDouble())
      }

    var trend = session.lastTrend
    if (session.lastProbability >= 0) {
      val delta = probability - session.lastProbability
      if (kotlin.math.abs(delta) > 0.0001) {
        trend = if (kotlin.math.abs(delta) < trendThreshold) 0.0 else delta
      }
    }

    val settingsHash = settings.hashCode()

    if (
      kotlin.math.abs(probability - session.lastProbability) < 0.0001 &&
        kotlin.math.abs(buffer - session.lastBuffer) < 0.01 &&
        ping == session.lastPing &&
        kotlin.math.abs(dmgMultiplier - session.lastDmgMultiplier) < 0.0001 &&
        kotlin.math.abs(trend - session.lastTrend) < 0.0001 &&
        settingsHash == session.lastSettingsHash &&
        session.lastSentComponent != null
    ) {
      session.lastTrend = trend
      session.cyclesSinceLastSend++
      if (session.cyclesSinceLastSend < session.keepAliveCycles) {
        return
      }
      sendActionBar(viewer, session.lastSentComponent!!)
      session.cyclesSinceLastSend = 0
      return
    }

    val newComponent =
      buildActionBar(viewer, target, probability, buffer, ping, dmgMultiplier, trend, settings)

    session.lastProbability = probability
    session.lastBuffer = buffer
    session.lastPing = ping
    session.lastDmgMultiplier = dmgMultiplier
    session.lastTrend = trend
    session.lastSettingsHash = settingsHash
    session.lastSentComponent = newComponent
    session.cyclesSinceLastSend = 0

    sendActionBar(viewer, newComponent)
  }

  private fun buildActionBar(
    viewer: Player,
    target: Player,
    probability: Double,
    buffer: Double,
    ping: Int,
    dmgMultiplier: Double,
    trend: Double,
    settings: MonitorSettings,
  ): Component {
    val config = configManager.monitorConfig

    val themeKey = settings.theme.name.lowercase(Locale.ROOT)
    val sepTemplate = config.getString("theme.$themeKey.sep", "<dark_gray>•</dark_gray>")
    val separator = MessageUtil.deserializeRaw(sepTemplate)

    var modeParts = config.getStringList("modes.${settings.mode.name.lowercase(Locale.ROOT)}")
    if (modeParts.isEmpty()) {
      modeParts = listOf("prob", "trend", "buffer")
    }

    val parts = ArrayList<Component>()

    for (part in modeParts) {
      val partComponent =
        buildPart(part, viewer, target, probability, buffer, ping, dmgMultiplier, trend, settings)
      if (partComponent != null) {
        parts.add(partComponent)
      }
    }

    if (parts.isEmpty()) {
      return Component.empty()
    }

    return Component.join(JoinConfiguration.separator(separator), parts)
  }

  private fun buildPart(
    part: String,
    viewer: Player,
    target: Player,
    probability: Double,
    buffer: Double,
    ping: Int,
    dmgMultiplier: Double,
    trend: Double,
    settings: MonitorSettings,
  ): Component? {
    val config = configManager.monitorConfig
    val themeKey = settings.theme.name.lowercase(Locale.ROOT)
    val lowerPart = part.lowercase(Locale.ROOT)

    val keepLength = config.getBoolean("behavior.keep-length", true)
    val showNeutral = config.getBoolean("behavior.show-neutral-when-hidden", true)

    return when (lowerPart) {
      "name" -> {
        val mode = settings.showName
        if (mode == MonitorNameMode.NEVER) {
          null
        } else if (mode == MonitorNameMode.AUTO && viewer.uniqueId == target.uniqueId) {
          null
        } else {
          val rawName = target.name
          val maxLength = config.getInt("behavior.name.max-length", 12)
          val truncateSuffix = config.getString("behavior.name.truncate-suffix", "…")
          var name = rawName
          if (maxLength > 0 && rawName.length > maxLength) {
            val cut = maxOf(1, maxLength - truncateSuffix.length)
            name = rawName.substring(0, cut) + truncateSuffix
          }
          val template = config.getString("theme.$themeKey.name", "<gray>@{name}</gray>")
          MessageUtil.deserializeRaw(applyTemplate(template, "name", name))
        }
      }
      "prob" -> {
        val template = config.getString("theme.$themeKey.prob", "{prob}%")
        val decimals = config.getInt("format.prob.decimals", 0)
        val probValue = formatDecimal(probability * 100.0, decimals)
        MessageUtil.deserializeRaw(applyTemplate(template, "prob", probValue))
      }
      "trend" -> {
        if (!settings.showTrend) {
          null
        } else {
          val template = config.getString("theme.$themeKey.trend", "{trend}")
          val decimals = config.getInt("format.trend.decimals", 2)
          val trendValue = formatSigned(trend, decimals)
          MessageUtil.deserializeRaw(applyTemplate(template, "trend", trendValue))
        }
      }
      "buffer" -> {
        val template = config.getString("theme.$themeKey.buffer", "◆ {buffer}")
        val decimals = config.getInt("format.buffer.decimals", 2)
        val bufferValue = formatDecimal(buffer, decimals)
        MessageUtil.deserializeRaw(applyTemplate(template, "buffer", bufferValue))
      }
      "ping" -> {
        if (!settings.showPing) {
          renderNeutral("behavior.neutral.ping", keepLength, showNeutral)
        } else {
          val template = config.getString("theme.$themeKey.ping", "Ping {ping}ms")
          val minWidth = config.getInt("format.ping.min-width", 0)
          val pingValue = padPing(ping, minWidth)
          MessageUtil.deserializeRaw(applyTemplate(template, "ping", pingValue))
        }
      }
      "dmg" -> {
        val hideWhenDefault = config.getBoolean("format.dmg.hide-when-default", false)
        if (hideWhenDefault && kotlin.math.abs(dmgMultiplier - DEFAULT_DMG_MULTIPLIER) < 0.0001) {
          renderNeutral("behavior.neutral.dmg", keepLength, showNeutral)
        } else if (!settings.showDmg) {
          renderNeutral("behavior.neutral.dmg", keepLength, showNeutral)
        } else {
          val template = config.getString("theme.$themeKey.dmg", "Dmg {dmg}x")
          val decimals = config.getInt("format.dmg.decimals", 2)
          val dmgValue = formatDecimal(dmgMultiplier, decimals)
          MessageUtil.deserializeRaw(applyTemplate(template, "dmg", dmgValue))
        }
      }
      else -> null
    }
  }

  private fun renderNeutral(key: String, keepLength: Boolean, showNeutral: Boolean): Component? {
    if (!keepLength || !showNeutral) {
      return null
    }
    val template = configManager.monitorConfig.getString(key, "")
    return if (template.isBlank()) null else MessageUtil.deserializeRaw(template)
  }

  private fun applyTemplate(template: String, key: String, value: String): String {
    return template.replace("{$key}", value)
  }

  private fun formatDecimal(value: Double, decimals: Int): String {
    return String.format(Locale.US, "%.${decimals}f", value)
  }

  private fun formatSigned(value: Double, decimals: Int): String {
    val formatted = formatDecimal(kotlin.math.abs(value), decimals)
    return (if (value >= 0) "+" else "-") + formatted
  }

  private fun padPing(ping: Int, minWidth: Int): String {
    val raw = ping.toString()
    if (minWidth <= 0 || raw.length >= minWidth) {
      return raw
    }
    return String.format(Locale.US, "%0${minWidth}d", ping)
  }

  private fun sendActionBar(player: Player?, message: Component) {
    if (player == null || !player.isOnline) return
    adventure.player(player).sendActionBar(message)
  }

  private enum class SettingKey(val label: String) {
    PING("ping"),
    DMG("dmg"),
    TREND("trend"),
  }

  private class MonitorSession(
    val targetUuid: UUID,
    val keepAliveCycles: Int,
    val noDataComponent: Component,
    val noAiCheckComponent: Component,
  ) {
    var task: TaskHandle? = null
    var lastSentComponent: Component? = null
    var lastProbability: Double = -1.0
    var lastBuffer: Double = -1.0
    var lastPing: Int = -1
    var lastDmgMultiplier: Double = -1.0
    var lastTrend: Double = 0.0
    var lastSettingsHash: Int = 0
    var cyclesSinceLastSend: Int = 0
  }

  private companion object {
    const val DEFAULT_DMG_MULTIPLIER = 1.0
    const val AUTO_TREND_THRESHOLD_SCALE = 0.5
    const val DEFAULT_KEEP_ALIVE_TICKS = 20L
  }
}
