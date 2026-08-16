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

import ac.shard.config.MitigationsFile
import ac.shard.config.yaml.PatchResult
import ac.shard.config.yaml.WriteOutcome
import ac.shard.config.yaml.YamlFileStore
import ac.shard.config.yaml.YamlPatcher
import java.io.File
import java.security.MessageDigest
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import ru.vyarus.yaml.updater.parse.comments.model.CmtTree

data class Change(val path: String, val was: String, val now: String)

data class PunishmentEdit(val group: String, val actions: Map<String, List<String>>)

data class MitigationEdit(val rules: List<Any?>?) {
  val empty: Boolean
    get() = rules == null
}

data class Delta(
  val changes: Map<String, List<Change>> = emptyMap(),
  val disabledRegions: Map<String, List<String>>? = null,
  val punishments: List<PunishmentEdit>? = null,
  val mitigations: MitigationEdit? = null,
)

sealed interface ApplyResult {
  data class Applied(val stamp: String, val count: Int) : ApplyResult

  data class Refused(val reasons: List<String>) : ApplyResult

  data class RolledBack(val reason: String) : ApplyResult
}

private const val CONFIG = "config.yml"
private const val PUNISHMENTS = "punishments.yml"
private const val WORLDGUARD_PATH = "ai/worldguard"
private const val REGIONS_KEY = "disabled-regions"
private const val ACTIONS_KEY = "actions"
private const val MITIGATIONS = "mitigations.yml"
private const val MAX_RULES = 64
private const val MAX_STEPS = 32
private const val MAX_ACTIONS_PER_STEP = 16
private val GROUP_NAME = Regex("[A-Za-z0-9_-]{1,32}")

private fun changeRefusals(file: String, changes: List<Change>): List<String> =
  changes.mapNotNull { change ->
    (EditorSchema.check(file, change.path, change.now) as? Verdict.Refused)?.let {
      "$file:${change.path} - ${it.reason}"
    }
  }

private fun regionRefusals(delta: Delta): List<String> {
  val entries = delta.disabledRegions ?: return emptyList()
  return listOfNotNull(
    (EditorSchema.checkRegions(entries) as? Verdict.Refused)?.let { "$REGIONS_KEY - ${it.reason}" }
  )
}

private fun groupShape(edit: PunishmentEdit): List<String> =
  when {
    !GROUP_NAME.matches(edit.group) -> listOf("punishment group ${edit.group} is not a name")
    edit.actions.size > MAX_STEPS -> listOf("${edit.group} has more than $MAX_STEPS steps")
    edit.actions.keys.any { (it.toIntOrNull() ?: 0) < 1 } ->
      listOf("${edit.group} has a step that is not a violation level")
    edit.actions.values.any { it.size > MAX_ACTIONS_PER_STEP } ->
      listOf("${edit.group} has a step with more than $MAX_ACTIONS_PER_STEP actions")
    else -> emptyList()
  }

private fun punishmentRefusals(delta: Delta): List<String> =
  delta.punishments.orEmpty().flatMap { edit ->
    groupShape(edit).ifEmpty {
      edit.actions.entries.flatMap { (level, actions) ->
        actions.mapNotNull { action ->
          (PunishmentActionRule.check(action) as? Verdict.Refused)?.let {
            "${edit.group} step $level - ${it.reason}"
          }
        }
      }
    }
  }

private sealed interface Rewritten {
  data class Body(val text: String) : Rewritten

  data class Failed(val reasons: List<String>) : Rewritten
}

private fun rewriteMitigations(text: String, edit: MitigationEdit): Rewritten {
  val rules = edit.rules
  return when {
    rules == null -> readBack(text)
    rules.size > MAX_RULES ->
      Rewritten.Failed(listOf("more than $MAX_RULES mitigation rules in one result"))
    else ->
      RulesBlock.replace(text, "rules", rules)?.let { readBack(it) }
        ?: Rewritten.Failed(listOf("$MITIGATIONS has no rules block to replace"))
  }
}

private fun readBack(body: String): Rewritten {
  val complaints = mutableListOf<String>()
  val parsed =
    runCatching {
        val node =
          YamlConfigurationLoader.builder().source { body.reader().buffered() }.build().load()
        MitigationsFile.read(node, complaints)
      }
      .getOrElse {
        return Rewritten.Failed(listOf("$MITIGATIONS would stop being readable: ${it.message}"))
      }
  return when {
    complaints.isNotEmpty() -> Rewritten.Failed(complaints.map { "$MITIGATIONS - $it" })
    parsed.rules.isEmpty() && body.contains("\nrules:") ->
      Rewritten.Failed(listOf("$MITIGATIONS - every rule was dropped, so nothing would apply"))
    else -> Rewritten.Body(body)
  }
}

@Suppress("TooManyFunctions")
internal class EditorApply(private val dataFolder: File, private val store: YamlFileStore) {

  @Synchronized
  fun apply(delta: Delta, baseline: Map<String, String> = emptyMap()): ApplyResult {
    val refusals =
      delta.changes.flatMap { (file, changes) -> changeRefusals(file, changes) } +
        regionRefusals(delta) +
        punishmentRefusals(delta) +
        movedUnderneath(delta, baseline)
    return if (refusals.isNotEmpty()) ApplyResult.Refused(refusals)
    else patch(withExtraFiles(delta))
  }

  fun backups(): List<String> = store.stamps()

  @Synchronized
  fun restore(stamp: String): ApplyResult =
    when (val outcome = store.restore(stamp)) {
      is WriteOutcome.Written -> ApplyResult.Applied(outcome.stamp, outcome.files.size)
      is WriteOutcome.Rejected -> ApplyResult.Refused(listOf(outcome.reason))
      is WriteOutcome.RolledBack -> ApplyResult.RolledBack(outcome.reason)
    }

  private fun movedUnderneath(delta: Delta, baseline: Map<String, String>): List<String> =
    buildList {
        if (delta.disabledRegions != null) add(CONFIG)
        if (delta.punishments != null) add(PUNISHMENTS)
        if (delta.mitigations != null) add(MITIGATIONS)
      }
      .distinct()
      .mapNotNull { file ->
        val expected = baseline[file] ?: return@mapNotNull null
        val target = File(dataFolder, file)
        val now = if (target.isFile) digestOf(target) else null
        if (now == expected) {
          null
        } else {
          "$file changed on the server since the editor was opened - reopen it"
        }
      }

  private fun digestOf(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }
  }

  private fun withExtraFiles(delta: Delta): Delta {
    val needed = buildList {
      if (delta.disabledRegions != null) add(CONFIG)
      if (delta.punishments != null) add(PUNISHMENTS)
      if (delta.mitigations != null) add(MITIGATIONS)
    }
    val added = needed.filterNot { it in delta.changes }.associateWith { emptyList<Change>() }
    return if (added.isEmpty()) delta else delta.copy(changes = delta.changes + added)
  }

  private fun patch(delta: Delta): ApplyResult {
    val trees = delta.changes.keys.associateWith { readTree(it) }
    val unreadable = trees.filterValues { it == null }.keys
    return if (unreadable.isNotEmpty()) {
      ApplyResult.Refused(unreadable.sorted().map { "$it could not be read" })
    } else {
      (stale(delta, trees) + invariants(delta, trees)).ifEmpty { null }?.let(ApplyResult::Refused)
        ?: write(delta, trees)
    }
  }

  private fun readTree(file: String): CmtTree? {
    val target = File(dataFolder, file)
    return if (!target.isFile) null else runCatching { YamlPatcher.read(target) }.getOrNull()
  }

  private fun stale(delta: Delta, trees: Map<String, CmtTree?>): List<String> =
    delta.changes.flatMap { (file, changes) ->
      val tree = trees.getValue(file)!!
      changes.mapNotNull { change ->
        val current = YamlPatcher.readScalar(tree, change.path)
        if (current == change.was) {
          null
        } else {
          "$file:${change.path} now reads $current, not ${change.was} - reopen the editor"
        }
      }
    }

  private fun invariants(delta: Delta, trees: Map<String, CmtTree?>): List<String> =
    delta.changes.keys.mapNotNull { file ->
      val tree = trees.getValue(file)!!
      val merged =
        EditorSchema.pairedPaths(file)
          .mapNotNull { path ->
            val value =
              delta.changes[file]?.firstOrNull { it.path == path }?.now
                ?: YamlPatcher.readScalar(tree, path)
            value?.let { path to unquote(it) }
          }
          .toMap()
      (EditorSchema.checkTogether(file, merged) as? Verdict.Refused)?.reason
    }

  private fun write(delta: Delta, trees: Map<String, CmtTree?>): ApplyResult {
    val failures = mutableListOf<String>()
    delta.changes.forEach { (file, changes) ->
      val tree = trees.getValue(file)!!
      changes.forEach { change ->
        when (val result = YamlPatcher.setScalar(tree, change.path, change.now)) {
          is PatchResult.Applied -> Unit
          is PatchResult.NotFound -> failures += "$file:${change.path} is not in the file"
          is PatchResult.Unsupported -> failures += "$file:${change.path} - ${result.reason}"
        }
      }
    }
    failures += sections(delta, trees)
    return if (failures.isNotEmpty()) ApplyResult.Refused(failures) else land(delta, trees)
  }

  private fun sections(delta: Delta, trees: Map<String, CmtTree?>): List<String> {
    val failures = mutableListOf<String>()
    delta.disabledRegions?.let { entries ->
      val done =
        YamlPatcher.setStringListMap(
          trees.getValue(CONFIG)!!,
          WORLDGUARD_PATH,
          REGIONS_KEY,
          entries,
        )
      if (done !is PatchResult.Applied) {
        failures += "$CONFIG:$WORLDGUARD_PATH is not in the file"
      }
    }
    delta.punishments.orEmpty().forEach { edit ->
      val done =
        YamlPatcher.setStringListMap(
          trees.getValue(PUNISHMENTS)!!,
          "Punishments/${edit.group}",
          ACTIONS_KEY,
          edit.actions,
        )
      if (done !is PatchResult.Applied) {
        failures += "$PUNISHMENTS: there is no punishment group named ${edit.group}"
      }
    }
    return failures
  }

  private fun land(delta: Delta, trees: Map<String, CmtTree?>): ApplyResult {
    val rendered = trees.mapValues { (_, tree) -> YamlPatcher.render(tree!!) }
    val edit = delta.mitigations
    val bodies =
      if (edit == null) {
        rendered
      } else {
        val body = rewriteMitigations(rendered.getValue(MITIGATIONS), edit)
        when (body) {
          is Rewritten.Failed -> return ApplyResult.Refused(body.reasons)
          is Rewritten.Body -> rendered + (MITIGATIONS to body.text)
        }
      }
    val total =
      delta.changes.values.sumOf { it.size } +
        (delta.disabledRegions?.let { 1 } ?: 0) +
        delta.punishments.orEmpty().size +
        (edit?.rules?.let { 1 } ?: 0)
    return when (val outcome = store.write(bodies)) {
      is WriteOutcome.Written -> ApplyResult.Applied(outcome.stamp, total)
      is WriteOutcome.Rejected -> ApplyResult.Refused(listOf(outcome.reason))
      is WriteOutcome.RolledBack -> ApplyResult.RolledBack(outcome.reason)
    }
  }
}
