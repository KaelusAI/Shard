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
package ac.shard.monitor.hud

import ac.shard.config.ConfigView
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.ticksToCycles
import java.util.logging.Logger

data class ActionBarConfig(val enabled: Boolean, val keepAliveCycles: Int) {
  companion object {
    fun from(config: ConfigView, updateTicks: Long): ActionBarConfig {
      val own = config.getLong("outputs.actionbar.keepalive-ticks", INHERIT_KEEPALIVE)
      val ticks = if (own <= 0L) MonitorBehaviorConfig.sharedKeepAliveTicks(config) else own
      return ActionBarConfig(
        enabled = config.getBoolean("outputs.actionbar.enabled", true),
        keepAliveCycles = ticksToCycles(ticks, updateTicks),
      )
    }
  }
}

data class SidebarConfig(
  val enabled: Boolean,
  val slot: Int,
  val title: String,
  val reassertCycles: Int,
  val dropBlankLines: Boolean,
  val unavailableLine: String,
  val targetSeparator: String,
  val lines: List<String>,
) {
  companion object {
    fun from(config: ConfigView, updateTicks: Long, viewSlot: Int, logger: Logger): SidebarConfig {
      val slot =
        config.getInt("outputs.sidebar.slot", DEFAULT_SIDEBAR_SLOT).coerceIn(0, MAX_DISPLAY_SLOT)
      val clashes = slot == viewSlot
      if (clashes) {
        logger.warning(
          "[Monitor] outputs.sidebar.slot ($slot) matches view.slot ($viewSlot); " +
            "the sidebar output is disabled. Change one of them."
        )
      }
      val reassertTicks =
        config.getLong("outputs.sidebar.reassert-ticks", DEFAULT_SIDEBAR_REASSERT_TICKS)
      val lines = config.getStringList("outputs.sidebar.lines").ifEmpty { DEFAULT_SIDEBAR_LINES }
      if (lines.size > SIDEBAR_MAX_LINES) {
        logger.warning(
          "[Monitor] outputs.sidebar.lines has ${lines.size} entries; " +
            "only the first $SIDEBAR_MAX_LINES are drawn."
        )
      }
      return SidebarConfig(
        enabled = config.getBoolean("outputs.sidebar.enabled", false) && !clashes,
        slot = slot,
        title = config.getString("outputs.sidebar.title", DEFAULT_SIDEBAR_TITLE),
        reassertCycles = if (reassertTicks <= 0L) 0 else ticksToCycles(reassertTicks, updateTicks),
        dropBlankLines = config.getBoolean("outputs.sidebar.drop-blank-lines", false),
        unavailableLine =
          config.getString("outputs.sidebar.unavailable-line", DEFAULT_SIDEBAR_UNAVAILABLE),
        targetSeparator = config.getString("outputs.sidebar.target-separator", ""),
        lines = lines.take(SIDEBAR_MAX_LINES),
      )
    }
  }
}

data class ChatConfig(
  val enabled: Boolean,
  val summaryCycles: Int,
  val summaryTemplate: String,
  val skipUnchanged: Boolean,
  val minProbability: Double,
  val alwaysShowFlagged: Boolean,
  val cooldownMillis: Long,
  val unknownPing: String,
  val liveTemplate: String,
  val flaggedTemplate: String,
) {
  companion object {
    fun from(config: ConfigView, updateTicks: Long): ChatConfig {
      val summaryTicks =
        config
          .getLong("outputs.chat.summary.interval-ticks", DEFAULT_CHAT_SUMMARY_TICKS)
          .coerceAtLeast(MIN_CHAT_SUMMARY_TICKS)
      val liveTemplate = config.getString("outputs.chat.live.template", DEFAULT_LIVE_TEMPLATE)
      val flagged = config.getString("outputs.chat.live.flagged-template", "")
      return ChatConfig(
        enabled = config.getBoolean("outputs.chat.enabled", false),
        summaryCycles = ticksToCycles(summaryTicks, updateTicks),
        summaryTemplate =
          config.getString("outputs.chat.summary.template", DEFAULT_CHAT_SUMMARY_TEMPLATE),
        skipUnchanged = config.getBoolean("outputs.chat.summary.skip-unchanged", true),
        minProbability =
          config.getDouble("outputs.chat.live.min-probability", 0.0).coerceIn(0.0, 1.0),
        alwaysShowFlagged = config.getBoolean("outputs.chat.live.always-show-flagged", true),
        cooldownMillis =
          config
            .getLong("outputs.chat.live.cooldown-ticks", DEFAULT_LIVE_COOLDOWN_TICKS)
            .coerceAtLeast(1L) * MILLIS_PER_TICK,
        unknownPing = config.getString("outputs.chat.live.unknown-ping", DEFAULT_UNKNOWN_PING),
        liveTemplate = liveTemplate,
        flaggedTemplate = flagged.ifBlank { liveTemplate },
      )
    }
  }
}

data class TabListConfig(val enabled: Boolean, val header: String, val footer: String) {
  companion object {
    fun from(config: ConfigView): TabListConfig =
      TabListConfig(
        enabled = config.getBoolean("outputs.tablist.enabled", false),
        header = config.getString("outputs.tablist.header", DEFAULT_TABLIST_HEADER),
        footer = config.getString("outputs.tablist.footer", DEFAULT_TABLIST_FOOTER),
      )
  }
}

data class MonitorOutputsConfig(
  val actionBar: ActionBarConfig,
  val bossBar: BossBarConfig,
  val sidebar: SidebarConfig,
  val chat: ChatConfig,
  val tabList: TabListConfig,
) {
  fun isEnabled(kind: MonitorOutputKind): Boolean =
    when (kind) {
      MonitorOutputKind.ACTIONBAR -> actionBar.enabled
      MonitorOutputKind.BOSSBAR -> bossBar.enabled
      MonitorOutputKind.SIDEBAR -> sidebar.enabled
      MonitorOutputKind.CHAT -> chat.enabled
      MonitorOutputKind.TABLIST -> tabList.enabled
    }

  companion object {
    fun from(
      config: ConfigView,
      updateTicks: Long,
      viewSlot: Int,
      logger: Logger,
    ): MonitorOutputsConfig =
      MonitorOutputsConfig(
        actionBar = ActionBarConfig.from(config, updateTicks),
        bossBar = BossBarConfig.from(config, logger),
        sidebar = SidebarConfig.from(config, updateTicks, viewSlot, logger),
        chat = ChatConfig.from(config, updateTicks),
        tabList = TabListConfig.from(config),
      )
  }
}

internal const val INHERIT_KEEPALIVE = -1L
internal const val SIDEBAR_MAX_LINES = 15
internal const val MILLIS_PER_TICK = 50L
internal const val DEFAULT_SIDEBAR_SLOT = 1
internal const val DEFAULT_SIDEBAR_REASSERT_TICKS = 100L
internal const val DEFAULT_SIDEBAR_TITLE = "<gradient:#8e9eab:#eef2f3>Shard</gradient>"
internal const val DEFAULT_SIDEBAR_UNAVAILABLE = "<gray>no data</gray>"
internal const val DEFAULT_CHAT_SUMMARY_TICKS = 200L
internal const val MIN_CHAT_SUMMARY_TICKS = 20L
internal const val DEFAULT_LIVE_COOLDOWN_TICKS = 20L
internal const val DEFAULT_UNKNOWN_PING = "--"
internal const val DEFAULT_CHAT_SUMMARY_TEMPLATE = "<prefix> {headline}"
internal const val DEFAULT_LIVE_TEMPLATE =
  "<prefix> <white>{name}</white> <gray>»</gray> {prob!} <gray>•</gray> {trend!} " +
    "<gray>•</gray> {buffer!}"
internal const val DEFAULT_TABLIST_HEADER = "<gradient:#8e9eab:#eef2f3>Shard Monitor</gradient>"
internal const val DEFAULT_TABLIST_FOOTER = "{headline}"
internal val DEFAULT_SIDEBAR_LINES =
  listOf(
    "<gray>Target</gray>  <white>{name}</white>",
    "",
    "<gray>Prob</gray>    {prob!}",
    "<gray>Trend</gray>   {trend!}",
    "<gray>Buffer</gray>  {buffer!}",
    "<gray>Ping</gray>    {ping!}",
    "<gray>Dmg</gray>     {dmg!}",
  )
