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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package ac.shard.utils

enum class Message(val path: String) {
  PREFIX("prefix"),
  ALERTS_ENABLED("alerts-enabled"),
  ALERTS_DISABLED("alerts-disabled"),
  ALERTS_FORMAT("alerts-format"),
  PLAYER_NOT_FOUND("player-not-found"),
  RUN_AS_PLAYER("run-as-player"),
  RELOAD_START("reload-start"),
  RELOAD_SUCCESS("reload-success"),
  STORAGE_DEGRADED("storage.degraded"),

  // Connect (device-link to the web panel)
  CONNECT_DISABLED("connect.disabled"),
  CONNECT_CONSOLE_ONLY("connect.console-only"),
  CONNECT_INSECURE_URL("connect.insecure-url"),
  CONNECT_START("connect.start"),
  CONNECT_URL("connect.url"),
  CONNECT_WAITING("connect.waiting"),
  CONNECT_RESTARTED("connect.restarted"),
  CONNECT_SUCCESS("connect.success"),
  CONNECT_DENIED("connect.denied"),
  CONNECT_EXPIRED("connect.expired"),
  CONNECT_ERROR("connect.error"),
  CONNECT_DISCONNECT_CONFIRM("connect.disconnect-confirm"),
  CONNECT_DISCONNECT_NO_PENDING("connect.disconnect-no-pending"),
  CONNECT_DISCONNECT_SUCCESS("connect.disconnect-success"),
  CONNECT_DISCONNECT_LOCAL_ONLY("connect.disconnect-local-only"),
  CONNECT_DISCONNECT_NOTHING("connect.disconnect-nothing"),
  CONNECT_STATUS_HEADER("connect.status-header"),
  CONNECT_STATUS_LINKED("connect.status-linked"),
  CONNECT_STATUS_KEY("connect.status-key"),
  CONNECT_STATUS_SERVER_URL("connect.status-server-url"),
  CONNECT_STATUS_QUOTA("connect.status-quota"),
  CONNECT_STATUS_QUOTA_UNAVAILABLE("connect.status-quota-unavailable"),
  CONNECT_STATUS_MODEL("connect.status-model"),
  CONNECT_STATUS_NOT_LINKED("connect.status-not-linked"),
  CONNECT_LINK_SUCCESS("connect.link-success"),
  CONNECT_LINK_INVALID("connect.link-invalid"),

  // Brand
  BRAND_ALERTS_ENABLED("brand.alerts-enabled"),
  BRAND_ALERTS_DISABLED("brand.alerts-disabled"),
  BRAND_NOTIFICATION("brand.notification"),
  BRAND_DISCONNECT_FORGE("brand.disconnect-forge"),

  // DataCollect
  DATACOLLECT_DETAILS_REQUIRED("datacollect.details-required"),
  DATACOLLECT_INVALID_TYPE("datacollect.invalid-type"),
  DATACOLLECT_START_SUCCESS("datacollect.start-success"),
  DATACOLLECT_START_RESTARTED("datacollect.start-restarted"),
  DATACOLLECT_STOP_SUCCESS("datacollect.stop-success"),
  DATACOLLECT_STOP_FAIL("datacollect.stop-fail"),
  DATACOLLECT_CANCEL_SUCCESS("datacollect.cancel-success"),
  DATACOLLECT_STATUS_HEADER("datacollect.status-header"),
  DATACOLLECT_STATUS_PLAYER("datacollect.status-player"),
  DATACOLLECT_STATUS_NONE("datacollect.status-none"),
  DATACOLLECT_STATUS_NO_SESSION("datacollect.status-no-session"),

  // Monitor
  MONITOR_ENABLED("monitor.enabled"),
  MONITOR_DISABLED("monitor.disabled"),
  MONITOR_NO_DATA("monitor.no-data"),
  MONITOR_NO_AICHECK("monitor.no-aicheck"),
  MONITOR_SETTING_UPDATED("monitor.setting-updated"),
  MONITOR_INVALID_SETTING("monitor.invalid-setting"),
  MONITOR_NO_PERMISSION_OTHER("monitor.no-permission-other"),
  MONITOR_NOT_ACTIVE("monitor.not-active"),
  MONITOR_RESET("monitor.reset"),
  MONITOR_LIST_HEADER("monitor.list-header"),
  MONITOR_LIST_ENTRY("monitor.list-entry"),
  MONITOR_LIST_EMPTY("monitor.list-empty"),
  MONITOR_PROB_DEPRECATED("monitor.prob-deprecated"),
  MONITOR_OUTPUT_UPDATED("monitor.output-updated"),
  MONITOR_OUTPUT_INVALID("monitor.output-invalid"),
  MONITOR_OUTPUT_NO_PERMISSION("monitor.output-no-permission"),
  MONITOR_OUTPUT_DISABLED("monitor.output-disabled"),
  MONITOR_OUTPUT_UNSUPPORTED("monitor.output-unsupported"),
  MONITOR_OUTPUT_LAST("monitor.output-last"),
  MONITOR_OUTPUT_HEADER("monitor.output-header"),
  MONITOR_OUTPUT_ENTRY("monitor.output-entry"),
  MONITOR_OUTPUT_ENTRY_HOVER("monitor.output-entry-hover"),
  MONITOR_OUTPUT_STATE_ACTIVE("monitor.output-state-active"),
  MONITOR_OUTPUT_STATE_AVAILABLE("monitor.output-state-available"),
  MONITOR_OUTPUT_STATE_LOCKED("monitor.output-state-locked"),
  MONITOR_OUTPUT_STATE_OFF("monitor.output-state-off"),
  MONITOR_OUTPUT_STATE_UNSUPPORTED("monitor.output-state-unsupported"),
  MONITOR_TARGET_ADDED("monitor.target-added"),
  MONITOR_TARGET_REMOVED("monitor.target-removed"),
  MONITOR_TARGET_ALREADY("monitor.target-already"),
  MONITOR_TARGET_NOT_WATCHED("monitor.target-not-watched"),
  MONITOR_TARGETS_CLEARED("monitor.targets-cleared"),
  MONITOR_TARGET_LIMIT("monitor.target-limit"),
  MONITOR_TARGET_OVERFLOW("monitor.target-overflow"),
  MONITOR_MULTI_NO_PERMISSION("monitor.multi-no-permission"),
  MONITOR_LIMIT_REACHED("monitor.limit-reached"),
  MONITOR_SETTINGS_HEADER("monitor.settings-header"),
  MONITOR_SETTINGS_ENTRY("monitor.settings-entry"),
  MONITOR_SETTINGS_ENTRY_HOVER("monitor.settings-entry-hover"),
  MONITOR_SETTINGS_HINT("monitor.settings-hint"),
  MONITOR_HELP("monitor.help"),
  VIEW_ENABLED("view.enabled"),
  VIEW_DISABLED("view.disabled"),

  // Profile
  PROFILE_NO_DATA("profile.no-data"),
  PROFILE_LINES("profile.lines"),

  // History
  HISTORY_DISABLED("history.disabled"),
  HISTORY_HEADER("history.header"),
  HISTORY_ENTRY("history.entry"),
  HISTORY_NO_VIOLATIONS("history.no-violations"),

  // Logs
  LOGS_HEADER("logs.header"),
  LOGS_ENTRY("logs.entry"),
  LOGS_NO_VIOLATIONS("logs.no-violations"),

  // Punish
  PUNISH_RESET_SUCCESS("punish.reset-success"),

  // Exempt
  EXEMPT_INVALID_DURATION("exempt.invalid-duration"),
  EXEMPT_SUCCESS_PERM("exempt.success-perm"),
  EXEMPT_SUCCESS_TEMP("exempt.success-temp"),
  EXEMPT_REMOVE_SUCCESS("exempt.remove-success"),
  EXEMPT_REMOVE_FAIL("exempt.remove-fail"),
  EXEMPT_STATUS_PERM_PERMISSION("exempt.status-perm-permission"),
  EXEMPT_STATUS_PERM_COMMAND("exempt.status-perm-command"),
  EXEMPT_STATUS_NOT_EXEMPT("exempt.status-not-exempt"),
  EXEMPT_STATUS_EXPIRED("exempt.status-expired"),
  EXEMPT_STATUS_TEMP("exempt.status-temp"),

  // Suspicious
  SUSPICIOUS_ALERTS_ENABLED("suspicious.alerts-enabled"),
  SUSPICIOUS_ALERTS_DISABLED("suspicious.alerts-disabled"),
  SUSPICIOUS_ALERT_TRIGGERED("suspicious.alert-triggered"),
  SUSPICIOUS_LIST_EMPTY("suspicious.list-empty"),
  SUSPICIOUS_LIST_HEADER("suspicious.list-header"),
  SUSPICIOUS_LIST_ENTRY("suspicious.list-entry"),
  SUSPICIOUS_LIST_ENTRY_HOVER("suspicious.list-entry-hover"),
  SUSPICIOUS_FLAGGED_EMPTY("suspicious.flagged-empty"),
  SUSPICIOUS_FLAGGED_HEADER("suspicious.flagged-header"),
  SUSPICIOUS_FLAGGED_ENTRY("suspicious.flagged-entry"),
  SUSPICIOUS_TOP_NONE("suspicious.top-none"),
  SUSPICIOUS_TOP_PLAYER("suspicious.top-player"),
  SUSPICIOUS_TOP_PLAYER_HOVER("suspicious.top-player-hover"),

  // Cross-server
  CROSS_SERVER_ALERT_PREFIX("cross-server.alert-prefix"),
  CROSS_SERVER_SERVER_TAG("cross-server.server-tag"),

  // Stats
  STATS_INVALID_PERIOD("stats.invalid-period"),
  STATS_HEADER("stats.header"),
  STATS_FLAGS("stats.flags"),
  STATS_FLAGS_HOVER("stats.flags-hover"),
  STATS_PLAYERS("stats.players"),
  STATS_PLAYERS_HOVER("stats.players-hover"),
  STATS_VIOLATORS("stats.violators"),
  STATS_VIOLATORS_HOVER("stats.violators-hover"),
  STATS_ONLINE("stats.online"),
  STATS_SUSPICIOUS("stats.suspicious"),
  STATS_SUSPICIOUS_HOVER("stats.suspicious-hover"),

  // Help
  HELP_MESSAGE("help"),

  // Internal
  INTERNAL_ERROR("internal.error"),

  // Time formats
  TIME_AGO("time.ago"),
  TIME_DAYS("time.days"),
  TIME_HOURS("time.hours"),
  TIME_MINUTES("time.minutes"),
  TIME_SECONDS("time.seconds"),
}
