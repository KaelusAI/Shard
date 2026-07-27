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
package ac.shard.monitor.view

import ac.shard.config.ConfigView
import ac.shard.monitor.core.ObjectiveSpec
import ac.shard.monitor.core.fillTemplate
import ac.shard.monitor.core.ticksToCycles
import java.util.UUID

internal data class ViewRuntimeConfig(
  val updateTicks: Long,
  val rebindCycles: Int,
  val resyncCycles: Int,
  val pingRefreshCycles: Int,
  val pingBucketMs: Int,
  val placement: ViewPlacement,
  val belowTitle: String,
  val fallbackProb: String,
  val fallbackBuffer: String,
  val probDecimals: Int,
  val bufferDecimals: Int,
  val prefixTemplate: String,
  val suffixTemplate: String,
  val belowTemplate: String,
  val defaultBelowText: String,
  val usesPing: Boolean,
  val slot: Int = BELOW_NAME_DISPLAY_SLOT,
) {
  fun objectiveSpec(objectiveName: String): ObjectiveSpec =
    ObjectiveSpec(
      name = objectiveName,
      slot = slot,
      title = belowTitle,
      legacyTitleFallback = LEGACY_BELOW_TITLE,
      blankScoreText = defaultBelowText,
    )

  companion object {
    fun from(config: ConfigView): ViewRuntimeConfig {
      val updateTicks = config.getLong("view.update", DEFAULT_UPDATE_TICKS).coerceAtLeast(1L)
      val rebindTicks = config.getLong("view.rebind-ticks", DEFAULT_REBIND_TICKS).coerceAtLeast(1L)
      val resyncTicks = config.getLong("view.resync-ticks", DEFAULT_RESYNC_TICKS).coerceAtLeast(1L)
      val pingRefreshTicks =
        config.getLong("view.ping-refresh-ticks", DEFAULT_PING_REFRESH_TICKS).coerceAtLeast(1L)
      val pingBucketMs =
        config.getInt("view.ping-bucket-ms", DEFAULT_PING_BUCKET_MS).coerceAtLeast(1)
      val prefixTemplate = config.getString("view.template.prefix", DEFAULT_PREFIX_TEMPLATE)
      val suffixTemplate = config.getString("view.template.suffix", DEFAULT_SUFFIX_TEMPLATE)
      val belowTemplate = config.getString("view.template.below", DEFAULT_BELOW_TEMPLATE)

      return ViewRuntimeConfig(
        updateTicks = updateTicks,
        rebindCycles = ticksToCycles(rebindTicks, updateTicks),
        resyncCycles = ticksToCycles(resyncTicks, updateTicks),
        pingRefreshCycles = ticksToCycles(pingRefreshTicks, updateTicks),
        pingBucketMs = pingBucketMs,
        placement = parseViewPlacement(config.getString("view.position", DEFAULT_VIEW_POSITION)),
        belowTitle = config.getString("view.template.below-title", DEFAULT_BELOW_TITLE),
        fallbackProb = config.getString("view.fallback.prob", DEFAULT_FALLBACK_PROB),
        fallbackBuffer = config.getString("view.fallback.buffer", DEFAULT_FALLBACK_BUFFER),
        probDecimals = config.getInt("view.format.prob-decimals", DEFAULT_PROB_DECIMALS),
        bufferDecimals = config.getInt("view.format.buffer-decimals", DEFAULT_BUFFER_DECIMALS),
        prefixTemplate = prefixTemplate,
        suffixTemplate = suffixTemplate,
        belowTemplate = belowTemplate,
        defaultBelowText =
          fillTemplate(
            belowTemplate,
            mapOf(
              "prob" to config.getString("view.fallback.prob", DEFAULT_FALLBACK_PROB),
              "buffer" to config.getString("view.fallback.buffer", DEFAULT_FALLBACK_BUFFER),
              "ping" to DEFAULT_FALLBACK_PING,
            ),
          ),
        usesPing =
          prefixTemplate.contains(PING_PLACEHOLDER) ||
            suffixTemplate.contains(PING_PLACEHOLDER) ||
            belowTemplate.contains(PING_PLACEHOLDER),
        slot = viewDisplaySlot(config),
      )
    }
  }
}

internal fun viewDisplaySlot(config: ConfigView): Int =
  config.getInt("view.slot", BELOW_NAME_DISPLAY_SLOT).coerceIn(0, MAX_DISPLAY_SLOT)

internal enum class ViewPlacement {
  ABOVE_NAME,
  BELOW_NAME,
}

internal fun parseViewPlacement(raw: String?): ViewPlacement {
  val normalized = raw?.trim().orEmpty()
  return when {
    normalized.equals("below", ignoreCase = true) -> ViewPlacement.BELOW_NAME
    normalized.equals("below_name", ignoreCase = true) -> ViewPlacement.BELOW_NAME
    else -> ViewPlacement.ABOVE_NAME
  }
}

internal fun objectiveNameForViewer(viewerId: UUID): String {
  val compact = viewerId.toString().replace("-", "")
  return OBJECTIVE_PREFIX + compact.substring(0, OBJECTIVE_HASH_LENGTH)
}

internal fun teamNameForView(uuid: UUID): String {
  val compact = uuid.toString().replace("-", "")
  return TEAM_PREFIX + compact.substring(0, TEAM_HASH_LENGTH)
}

internal const val TEAM_PREFIX = "slv_"
internal const val TEAM_HASH_LENGTH = 12
internal const val OBJECTIVE_PREFIX = "svw_"
internal const val OBJECTIVE_HASH_LENGTH = 12

internal const val DEFAULT_UPDATE_TICKS = 2L
internal const val DEFAULT_REBIND_TICKS = 100L
internal const val DEFAULT_RESYNC_TICKS = 100L
internal const val DEFAULT_PING_REFRESH_TICKS = 20L
internal const val DEFAULT_PING_BUCKET_MS = 10
internal const val BELOW_NAME_DISPLAY_SLOT = 2
internal const val MAX_DISPLAY_SLOT = 2
internal const val LEGACY_BELOW_TITLE = "% AI"
internal const val DEFAULT_VIEW_POSITION = "BELOW_NAME"
internal const val DEFAULT_BELOW_TITLE = ""
internal const val DEFAULT_FALLBACK_PROB = "--"
internal const val DEFAULT_FALLBACK_BUFFER = "--"
internal const val DEFAULT_PROB_DECIMALS = 0
internal const val DEFAULT_BUFFER_DECIMALS = 2
internal const val DEFAULT_FALLBACK_PING = "--"
internal const val PING_PLACEHOLDER = "{ping}"
internal const val DEFAULT_PREFIX_TEMPLATE =
  "<dark_gray>[</dark_gray><white>{prob}%</white><dark_gray> • </dark_gray>" +
    "<yellow>{buffer}</yellow><dark_gray> • </dark_gray>" +
    "<aqua>{ping}ms</aqua><dark_gray>]</dark_gray> "
internal const val DEFAULT_SUFFIX_TEMPLATE = ""
internal const val DEFAULT_BELOW_TEMPLATE =
  "<dark_gray>[</dark_gray><white>{prob}%</white><dark_gray> • </dark_gray>" +
    "<yellow>{buffer}</yellow><dark_gray> • </dark_gray>" +
    "<aqua>{ping}ms</aqua><dark_gray>]</dark_gray>"
