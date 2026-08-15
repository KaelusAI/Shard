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
package ac.shard.editor

enum class DiffWeight {
  ORDINARY,
  NOTABLE,
}

data class DiffRow(
  val file: String,
  val key: String,
  val before: String,
  val after: String,
  val weight: DiffWeight,
)

@Suppress("TooManyFunctions")
internal object EditorDiff {

  fun rows(delta: Delta): List<DiffRow> =
    delta.changes.entries
      .sortedBy { it.key }
      .flatMap { (file, changes) ->
        changes.map { DiffRow(file, named(file, it.path), it.was, it.now, weigh(file, it)) }
      } + regionRows(delta) + punishmentRows(delta) + mitigationRows(delta)

  private fun named(file: String, path: String): String {
    val dotted = path.replace('/', '.')
    return if (file == "config.yml") dotted else "${file.removeSuffix(".yml")}.$dotted"
  }

  fun needsConfirming(rows: List<DiffRow>): Boolean = rows.any { it.weight == DiffWeight.NOTABLE }

  fun visible(rows: List<DiffRow>, room: Int): List<DiffRow> {
    val (notable, ordinary) = rows.partition { it.weight == DiffWeight.NOTABLE }
    return notable + ordinary.take((room - notable.size).coerceAtLeast(0))
  }

  private fun weigh(file: String, change: Change): DiffWeight =
    if (EditorSchema.loosens(file, change.path, change.was, change.now)) {
      DiffWeight.NOTABLE
    } else {
      DiffWeight.ORDINARY
    }

  private fun regionRows(delta: Delta): List<DiffRow> {
    val entries = delta.disabledRegions ?: return emptyList()
    val total = entries.values.sumOf { it.size }
    return listOf(
      DiffRow(
        "config.yml",
        "ai.worldguard.disabled-regions",
        "",
        entries.entries
          .sortedBy { it.key }
          .joinToString("; ") {
            "${it.key}: ${it.value.joinToString(", ")}"
          },
        if (total == 0) DiffWeight.ORDINARY else DiffWeight.NOTABLE,
      )
    )
  }

  private fun mitigationRows(delta: Delta): List<DiffRow> {
    val edit = delta.mitigations ?: return emptyList()
    return buildList {
      edit.rules?.let { rules ->
        add(
          DiffRow(
            "mitigations.yml",
            "mitigations.rules",
            "",
            if (rules.isEmpty()) "none left" else rules.joinToString(" · ", transform = ::ruleName),
            if (rules.any { !running(it) } || rules.isEmpty()) {
              DiffWeight.NOTABLE
            } else {
              DiffWeight.ORDINARY
            },
          )
        )
      }
    }
  }

  private fun running(rule: Any?): Boolean = (rule as? Map<*, *>)?.get("enabled") != false

  private fun ruleName(rule: Any?): String {
    val id = (rule as? Map<*, *>)?.get("id")?.toString().orEmpty()
    val off = (rule as? Map<*, *>)?.get("enabled") == false
    return when {
      id.isEmpty() -> "?"
      off -> "$id (off)"
      else -> id
    }
  }

  private fun punishmentRows(delta: Delta): List<DiffRow> =
    delta.punishments.orEmpty().flatMap { edit ->
      val steps = edit.actions.entries.sortedBy { it.key.toIntOrNull() ?: 0 }
      steps.mapIndexed { index, step ->
        DiffRow(
          "punishments.yml",
          "${edit.group} ${span(step.key, steps.getOrNull(index + 1)?.key)}",
          "",
          step.value.joinToString(" · "),
          if (step.value.any { terminal(it) || PunishmentActionRule.needsConfirming(it) }) {
            DiffWeight.NOTABLE
          } else {
            DiffWeight.ORDINARY
          },
        )
      }
    }

  private fun span(level: String, next: String?): String {
    val from = level.toIntOrNull() ?: return level
    val until = next?.toIntOrNull()
    return when {
      until == null -> "$from+"
      until - 1 <= from -> "$from"
      else -> "$from-${until - 1}"
    }
  }

  private fun terminal(action: String): Boolean {
    val verb = action.trim().substringBefore(' ').lowercase()
    return verb == "ban" || verb == "tempban" || verb == "kick"
  }
}
