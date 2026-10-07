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

import ac.shard.ai.label.LabelCatalog
import ac.shard.ai.label.LabelKey
import ac.shard.config.ConfigView
import ac.shard.detection.ModelCard
import ac.shard.monitor.core.MonitorSample
import ac.shard.monitor.core.fillTemplate
import ac.shard.monitor.core.formatDecimal
import kotlin.math.floor

internal data class ViewCardConfig(
  val header: String = DEFAULT_CARD_HEADER,
  val model: String = DEFAULT_CARD_MODEL,
  val row: String = DEFAULT_CARD_ROW,
  val singleRow: String = DEFAULT_CARD_SINGLE_ROW,
  val bar: String = DEFAULT_CARD_BAR,
  val footer: String = "",
  val unavailable: String = DEFAULT_CARD_UNAVAILABLE,
  val maxRows: Int = DEFAULT_CARD_MAX_ROWS,
  val bufferDecimals: Int = 1,
  val cells: Int = DEFAULT_CARD_CELLS,
  val warnAt: Double = DEFAULT_CARD_WARN_AT,
  val calm: String = "#A8A8A8",
  val warn: String = "#F0B429",
  val flagged: String = "#FF6A3D",
  val empty: String = "#3A3A3A",
  val rotateMillis: Long = DEFAULT_CARD_ROTATE_TICKS * MILLIS_PER_TICK,
) {
  val templates: List<String>
    get() = listOf(header, model, row, singleRow, bar, footer)

  companion object {
    fun from(config: ConfigView): ViewCardConfig {
      val d = ViewCardConfig()
      fun text(key: String, def: String) = config.getString("view.card.$key", def)
      return ViewCardConfig(
        header = text("header", d.header),
        model = text("model", d.model),
        row = text("row", d.row),
        singleRow = text("single-row", d.singleRow),
        bar = text("bar", d.bar),
        footer = text("footer", d.footer),
        unavailable = text("unavailable", d.unavailable),
        maxRows = config.getInt("view.card.max-rows", d.maxRows).coerceAtLeast(0),
        bufferDecimals =
          config
            .getInt("view.card.buffer-decimals", d.bufferDecimals)
            .coerceIn(0, MAX_CARD_DECIMALS),
        cells = config.getInt("view.card.bar-cells", d.cells).coerceIn(1, MAX_CARD_CELLS),
        warnAt = config.getDouble("view.card.bar-warn-at", d.warnAt),
        calm = text("colors.calm", d.calm),
        warn = text("colors.warn", d.warn),
        flagged = text("colors.flag", d.flagged),
        empty = text("colors.empty", d.empty),
        rotateMillis =
          config.getLong("view.card.rotate-ticks", DEFAULT_CARD_ROTATE_TICKS).coerceAtLeast(0L) *
            MILLIS_PER_TICK,
      )
    }
  }
}

internal class ViewCardRenderer(private val labelCatalog: LabelCatalog) {
  fun render(
    sample: MonitorSample,
    values: Map<String, String>,
    config: ViewCardConfig,
    titled: Boolean = sample.models.size > 1,
  ): String {
    val head = fillTemplate(config.header, values)
    val body =
      if (!sample.aiActive || sample.models.isEmpty()) {
        listOf(fillTemplate(config.unavailable, values))
      } else {
        sample.models.flatMap { section(it, titled, values, config) }
      }
    val foot = fillTemplate(config.footer, values)
    return (listOf(head) + body + listOf(foot)).filter { it.isNotBlank() }.joinToString(NEWLINE)
  }

  private fun section(
    card: ModelCard,
    titled: Boolean,
    values: Map<String, String>,
    config: ViewCardConfig,
  ): List<String> {
    val title =
      if (titled) listOf(fillTemplate(config.model, values + ("model" to card.title)))
      else emptyList()
    val template = if (card.rows.size == 1) config.singleRow else config.row
    val rows =
      card.rows.take(config.maxRows).map {
        fillTemplate(
          template,
          values +
            mapOf(
              "label" to
                if (it.label.substringAfter('/') == LabelKey.UNATTRIBUTED) ""
                else labelCatalog.displayName(it.label),
              "prob" to formatDecimal(it.probability * PERCENT, 0),
              "buffer" to formatDecimal(it.buffer, config.bufferDecimals),
            ),
        )
      }
    val bar =
      fillTemplate(
        config.bar,
        values +
          mapOf(
            "bar" to bar(card.buffer, card.flag, config),
            "buffer" to floor(card.buffer).toInt().toString(),
            "flag" to formatDecimal(card.flag, 0),
          ),
      )
    return title + rows + bar
  }

  private fun bar(buffer: Double, flag: Double, config: ViewCardConfig): String {
    val ratio = if (flag > 0.0) (buffer / flag).coerceIn(0.0, 1.0) else 0.0
    val filled = floor(ratio * config.cells).toInt()
    val color =
      when {
        flag > 0.0 && buffer >= flag -> config.flagged
        ratio >= config.warnAt -> config.warn
        else -> config.calm
      }
    val on = FILLED.repeat(filled)
    val off = EMPTY.repeat(config.cells - filled)
    return (if (on.isEmpty()) "" else "<color:$color>$on</color>") +
      if (off.isEmpty()) "" else "<color:${config.empty}>$off</color>"
  }

  private companion object {
    const val NEWLINE = "<newline>"
    const val FILLED = "▰"
    const val EMPTY = "▱"
    const val PERCENT = 100.0
  }
}

internal const val DEFAULT_CARD_HEADER = ""
internal const val DEFAULT_CARD_MODEL = "<gray>{model}</gray>"
internal const val DEFAULT_CARD_ROW =
  "<color:#C4B5FD>{label}</color>  <white>{prob}%</white>  <yellow>◆ {buffer}</yellow>"
internal const val DEFAULT_CARD_SINGLE_ROW =
  "<color:#C4B5FD>{label}</color>  <white>{prob}%</white>"
internal const val DEFAULT_CARD_BAR = "{bar}  <white>{buffer}</white><gray> / {flag}</gray>"
internal const val DEFAULT_CARD_UNAVAILABLE = "<gray>no data</gray>"
internal const val DEFAULT_CARD_MAX_ROWS = 4
internal const val DEFAULT_CARD_ROTATE_TICKS = 60L
private const val MILLIS_PER_TICK = 50L
internal const val DEFAULT_CARD_CELLS = 10
internal const val DEFAULT_CARD_WARN_AT = 0.5
private const val MAX_CARD_CELLS = 40
private const val MAX_CARD_DECIMALS = 3
