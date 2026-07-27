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

import ac.shard.config.ConfigManager
import ac.shard.config.ConfigView
import ac.shard.monitor.core.MonitorFormatConfig
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorThemeTable
import ac.shard.monitor.core.MonitorToken
import ac.shard.monitor.core.ticksToCycles
import ac.shard.monitor.view.viewDisplaySlot
import java.util.Locale
import java.util.logging.Logger

data class MonitorBehaviorConfig(
  val keepLength: Boolean,
  val showNeutralWhenHidden: Boolean,
  val neutralPing: String,
  val neutralDmg: String,
  val neutralTrend: String,
  val nameMaxLength: Int,
  val nameTruncateSuffix: String,
  val keepAliveCycles: Int,
  val pingRefreshCycles: Int,
  val pingBucketMs: Int,
  val trendDecayCycles: Int,
) {
  companion object {
    fun from(config: ConfigView, updateTicks: Long): MonitorBehaviorConfig =
      MonitorBehaviorConfig(
        keepLength = config.getBoolean("behavior.keep-length", true),
        showNeutralWhenHidden = config.getBoolean("behavior.show-neutral-when-hidden", true),
        neutralPing = config.getString("behavior.neutral.ping", DEFAULT_NEUTRAL_PING),
        neutralDmg = config.getString("behavior.neutral.dmg", DEFAULT_NEUTRAL_DMG),
        neutralTrend = config.getString("behavior.neutral.trend", DEFAULT_NEUTRAL_TREND),
        nameMaxLength =
          config.getInt("behavior.name.max-length", DEFAULT_NAME_MAX_LENGTH).coerceAtLeast(0),
        nameTruncateSuffix =
          config.getString("behavior.name.truncate-suffix", DEFAULT_TRUNCATE_SUFFIX),
        keepAliveCycles = ticksToCycles(sharedKeepAliveTicks(config), updateTicks),
        pingRefreshCycles =
          ticksToCycles(
            config
              .getLong("behavior.ping-refresh-ticks", DEFAULT_HUD_PING_REFRESH_TICKS)
              .coerceAtLeast(1L),
            updateTicks,
          ),
        pingBucketMs =
          config.getInt("behavior.ping-bucket-ms", DEFAULT_HUD_PING_BUCKET_MS).coerceAtLeast(1),
        trendDecayCycles =
          ticksToCycles(
            config
              .getLong("behavior.trend-decay-ticks", DEFAULT_TREND_DECAY_TICKS)
              .coerceAtLeast(1L),
            updateTicks,
          ),
      )

    internal fun sharedKeepAliveTicks(config: ConfigView): Long =
      config.getLong("behavior.keepalive-ticks", DEFAULT_KEEPALIVE_TICKS).coerceAtLeast(1L)
  }
}

data class MonitorLimitsConfig(val maxSessions: Int, val maxViewersPerTarget: Int) {
  companion object {
    fun from(config: ConfigView): MonitorLimitsConfig =
      MonitorLimitsConfig(
        maxSessions = config.getInt("limits.max-sessions", DEFAULT_MAX_SESSIONS).coerceAtLeast(0),
        maxViewersPerTarget =
          config
            .getInt("limits.max-viewers-per-target", DEFAULT_MAX_VIEWERS_PER_TARGET)
            .coerceAtLeast(0),
      )
  }
}

data class MonitorHudRuntimeConfig(
  val updateTicks: Long,
  val behavior: MonitorBehaviorConfig,
  val format: MonitorFormatConfig,
  val modes: Map<MonitorMode, List<MonitorToken>>,
  val themes: MonitorThemeTable,
  val limits: MonitorLimitsConfig,
  val outputs: MonitorOutputsConfig,
) {
  val actionBar: ActionBarConfig
    get() = outputs.actionBar

  val bossBar: BossBarConfig
    get() = outputs.bossBar

  val sidebar: SidebarConfig
    get() = outputs.sidebar

  val chat: ChatConfig
    get() = outputs.chat

  val tabList: TabListConfig
    get() = outputs.tabList

  fun isEnabled(kind: MonitorOutputKind): Boolean = outputs.isEnabled(kind)

  fun tokens(mode: MonitorMode): List<MonitorToken> = modes[mode] ?: FALLBACK_TOKENS

  companion object {
    fun fromManager(configManager: ConfigManager, logger: Logger): MonitorHudRuntimeConfig =
      from(configManager.monitorConfig, viewDisplaySlot(configManager.monitorConfig), logger)

    fun from(config: ConfigView, viewSlot: Int, logger: Logger): MonitorHudRuntimeConfig {
      val updateTicks = config.getLong("update", DEFAULT_HUD_UPDATE_TICKS).coerceAtLeast(1L)
      return MonitorHudRuntimeConfig(
        updateTicks = updateTicks,
        behavior = MonitorBehaviorConfig.from(config, updateTicks),
        format = MonitorFormatConfig.from(config),
        modes = readModes(config, logger),
        themes = MonitorThemeTable.from(config),
        limits = MonitorLimitsConfig.from(config),
        outputs = MonitorOutputsConfig.from(config, updateTicks, viewSlot, logger),
      )
    }

    private fun readModes(
      config: ConfigView,
      logger: Logger,
    ): Map<MonitorMode, List<MonitorToken>> =
      MonitorMode.entries.associateWith { mode ->
        val name = mode.name.lowercase(Locale.ROOT)
        val raw = config.getStringList("modes.$name")
        val parsed = raw.mapNotNull(MonitorToken::fromConfig)
        if (raw.isNotEmpty() && parsed.isEmpty()) {
          logger.warning("[Monitor] modes.$name lists no known tokens; using the built-in list.")
        }
        parsed.ifEmpty { FALLBACK_TOKENS }
      }
  }
}

internal val FALLBACK_TOKENS = listOf(MonitorToken.PROB, MonitorToken.TREND, MonitorToken.BUFFER)

internal const val DEFAULT_HUD_UPDATE_TICKS = 2L
internal const val DEFAULT_KEEPALIVE_TICKS = 20L
internal const val DEFAULT_HUD_PING_REFRESH_TICKS = 20L
internal const val DEFAULT_HUD_PING_BUCKET_MS = 10
internal const val DEFAULT_TREND_DECAY_TICKS = 100L
internal const val DEFAULT_NAME_MAX_LENGTH = 12
internal const val DEFAULT_TRUNCATE_SUFFIX = "…"
internal const val DEFAULT_NEUTRAL_PING = "<gray>Ping --</gray>"
internal const val DEFAULT_NEUTRAL_DMG = "<gray>Dmg 1.00x</gray>"
internal const val DEFAULT_NEUTRAL_TREND = "<gray>+0.00</gray>"
internal const val DEFAULT_MAX_SESSIONS = 32
internal const val DEFAULT_MAX_VIEWERS_PER_TARGET = 0
internal const val MAX_DISPLAY_SLOT = 2
