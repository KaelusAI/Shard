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
package ac.shard.punishment

import ac.shard.Shard
import ac.shard.ai.label.DetectionKey
import ac.shard.ai.label.LabelMode
import ac.shard.alert.AlertManager
import ac.shard.alert.AlertType
import ac.shard.api.event.punishment.PunishmentEvent
import ac.shard.api.impl.PunishmentGroupView
import ac.shard.api.impl.SessionView
import ac.shard.api.impl.ShardInitiator
import ac.shard.api.impl.event.PunishmentEventImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.api.impl.event.ViolationLevelResetEventImpl
import ac.shard.api.impl.labelIdOfAddress
import ac.shard.api.punishment.PunishmentGroup
import ac.shard.config.ConfigManager
import ac.shard.database.AiFacts
import ac.shard.database.DatabaseManager
import ac.shard.database.Violation
import ac.shard.detection.EffectiveSettings
import ac.shard.mitigation.MitigationSkip
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.Message
import ac.shard.utils.Messages
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import org.bukkit.plugin.IllegalPluginAccessException

@Suppress("LongParameterList")
class PunishmentManager(
  private val messages: Messages,
  private val plugin: Shard,
  private val logger: Logger,
  private val configManager: ConfigManager,
  private val databaseManager: DatabaseManager,
  private val alertManager: AlertManager,
  private val adventure: BukkitAudiences,
  private val scheduler: SchedulerService,
  private val events: ShardEvents,
  private val mitigationSkip: MitigationSkip,
) {
  private val flagLocks = Array(LOCK_STRIPES) { Any() }

  private class Step(val group: PunishNode, val vl: Int, val action: PunishAction?)

  private val reportedUnmatched = ConcurrentHashMap.newKeySet<String>()

  private class Flag(
    val player: ShardPlayer,
    val playerName: String,
    val checkName: String,
    val verbose: String,
    val labels: List<String>,
    val stored: String,
    val facts: AiFacts,
    val at: Instant = Instant.now(),
  )

  fun groupsFor(labels: Set<DetectionKey>): List<PunishNode> {
    val tree = configManager.punishmentTree
    if (labels.isEmpty()) return listOfNotNull(tree.route(""))
    return labels.mapNotNull { tree.route(it, singleHead(it)) }.distinct()
  }

  fun handleFlag(
    player: ShardPlayer,
    checkName: String,
    labels: Set<DetectionKey>,
    debug: String,
  ) {
    val playerName = player.player.name
    val groups = groupsFor(labels)
    val addresses = labels.map { it.address() }
    if (groups.isEmpty()) {
      reportUnmatchedFlag(checkName, playerName, addresses.toSet())
      return
    }

    val stored = joinForStorage(addresses)
    val flag = Flag(player, playerName, checkName, debug, addresses, stored, AiFacts.of(player))
    val lock = flagLocks[Math.floorMod(player.uuid.hashCode(), LOCK_STRIPES)]
    schedule {
      scheduler.runAsync {
        try {
          advance(flag, record(flag, groups, lock), 0)
        } catch (e: Exception) {
          logger.warning("[Punish] Failed to record the flag: ${e.message}")
        }
      }
    }
  }

  private fun record(flag: Flag, groups: List<PunishNode>, lock: Any): List<Step> {
    val reached =
      synchronized(lock) {
        val now = System.currentTimeMillis()
        groups.mapNotNull { group ->
          val since = if (group.expireMillis > 0L) now - group.expireMillis else 0L
          val count = databaseManager.database.recordFlag(flag.player.uuid, group.key, now, since)
          group.actionAt(count)?.let { set -> Triple(group, count, set) }
        }
      }
    val actions = configManager.punishmentTree.actions
    return reached.flatMap { (group, count, set) ->
      val own = actions[set] ?: return@flatMap emptyList()
      listOf(Step(group, count, null)) + own.map { Step(group, count, it) }
    }
  }

  private fun advance(flag: Flag, steps: List<Step>, index: Int) {
    if (index >= steps.size || !plugin.isEnabled) return
    try {
      perform(flag, steps, index)
    } catch (e: Exception) {
      logger.warning("[Punish] Failed to execute punishment actions: ${e.message}")
    }
  }

  @Suppress("SpreadOperator")
  private fun perform(flag: Flag, steps: List<Step>, index: Int) {
    val step = steps[index]
    val next = { advance(flag, steps, index + 1) }
    when (val action = step.action) {
      null ->
        schedule {
          scheduler.runAsync {
            if (fireGroup(flag, step)) next() else advance(flag, steps, nextGroup(steps, index))
          }
        }
      PunishAction.Alert -> onMain(next) { sendAlert(flag, step.vl) }
      PunishAction.Log -> {
        val violation = violationOf(flag, step.vl)
        offMain(next) { databaseManager.database.logAlert(violation) }
      }
      PunishAction.Reset -> offMain(next) { reset(flag, step.group) }
      is PunishAction.Broadcast -> {
        val component: Component = messages.format(action.template, *placeholders(flag, step.vl))
        onMain(next) { adventure.players().sendMessage(component) }
      }
      is PunishAction.Wait -> wait(action, next)
      is PunishAction.Console -> {
        val command = consoleCommand(action, flag, step.vl)
        onMain(next) { plugin.server.dispatchCommand(plugin.server.consoleSender, command) }
      }
    }
  }

  private fun onMain(next: () -> Unit, work: () -> Unit) {
    schedule {
      scheduler.runSync {
        work()
        next()
      }
    }
  }

  private fun offMain(next: () -> Unit, work: () -> Unit) {
    schedule {
      scheduler.runAsync {
        work()
        next()
      }
    }
  }

  private fun nextGroup(steps: List<Step>, index: Int): Int {
    var i = index + 1
    while (i < steps.size && steps[i].action != null) i++
    return i
  }

  private fun fireGroup(flag: Flag, step: Step): Boolean {
    if (!events.wants(PunishmentEvent::class.java)) return true
    val view =
      PunishmentGroupView.tree(configManager.punishmentTree.root).firstOrNull {
        it.key() == step.group.key
      }
    return view != null && !events.fire(groupEvent(flag, step, view)).isCancelled
  }

  private fun groupEvent(flag: Flag, step: Step, view: PunishmentGroup): PunishmentEventImpl {
    val primary = configManager.streamProfile?.primary?.id.orEmpty()
    val labels = visible(flag.labels).mapNotNullTo(mutableSetOf()) { labelIdOfAddress(it, primary) }
    val session =
      flag.player
        .takeIf { it.isAttached && !it.sessionEnded }
        ?.let { SessionView(it, configManager, mitigationSkip) }
    return PunishmentEventImpl(flag.player.uuid, flag.playerName, view, step.vl, labels, session)
  }

  private fun violationOf(flag: Flag, vl: Int): Violation =
    Violation(
      serverName = configManager.config.getString("history.server-name", "server"),
      playerUUID = flag.player.uuid,
      playerName = flag.playerName,
      checkName = flag.checkName,
      verbose = flag.verbose,
      vl = vl,
      createdAt = flag.at,
      labels = flag.stored,
      aiBuffer = flag.facts.buffer,
      mitigationScore = flag.facts.score,
      windows = flag.facts.windows,
      highWindows = flag.facts.highWindows,
      trail = flag.facts.trail,
    )

  private fun reset(flag: Flag, group: PunishNode) {
    databaseManager.database.resetViolationLevel(flag.player.uuid, group.key)
    events.publish(
      ViolationLevelResetEventImpl(flag.player.uuid, flag.playerName, ShardInitiator, group.key)
    )
  }

  private fun consoleCommand(action: PunishAction.Console, flag: Flag, vl: Int): String =
    action.template
      .replace("<player>", flag.playerName)
      .replace("<uuid>", flag.player.uuid.toString())
      .replace("<check_name>", flag.checkName)
      .replace("<vl>", vl.toString())
      .replace("<verbose>", flag.verbose)
      .replace("<labels>", displayLabels(flag.labels))

  private fun schedule(block: () -> Unit) {
    try {
      block()
    } catch (_: IllegalPluginAccessException) {
      return
    }
  }

  private fun singleHead(key: DetectionKey): Boolean {
    val model = configManager.streamProfile?.model(key.model) ?: return true
    val settings = EffectiveSettings.of(model, configManager.settings.localAi)
    return !(settings.split && settings.labelMode == LabelMode.MULTI_CLASS)
  }

  private fun placeholders(flag: Flag, vl: Int): Array<String> =
    arrayOf(
      "player",
      flag.playerName,
      "uuid",
      flag.player.uuid.toString(),
      "check_name",
      flag.checkName,
      "vl",
      vl.toString(),
      "verbose",
      flag.verbose.replace("\n", " | "),
      "labels",
      displayLabels(flag.labels),
      "labels_line",
      labelsLine(flag.labels),
    )

  private fun wait(action: PunishAction.Wait, next: () -> Unit) {
    val ticks = action.ticks
    when {
      ticks == null -> {
        logger.warning(
          "[Punish] [wait] does not understand '${action.argument}', use for example 30s or 2m"
        )
        next()
      }
      ticks > 0 -> schedule { scheduler.runLater({ next() }, ticks) }
      else -> next()
    }
  }

  @Suppress("SpreadOperator")
  private fun sendAlert(flag: Flag, vl: Int) {
    val message =
      messages.getMessage(
        Message.ALERTS_FORMAT,
        "check_label",
        configManager.labelCatalog.decorate(flag.checkName, visible(flag.labels)),
        *placeholders(flag, vl),
      )
    alertManager.send(message, AlertType.REGULAR)
  }

  private fun reportUnmatchedFlag(checkName: String, playerName: String, labels: Set<String>) {
    val message =
      "[Punish] $checkName flag on $playerName went unpunished: no group matches labels " +
        "$labels. Groups: ${configManager.punishmentTree.root.groups.map { it.name }}"
    val tree = System.identityHashCode(configManager.punishmentTree)
    if (!reportedUnmatched.add("$tree|$checkName|$playerName|${labels.sorted()}")) {
      logger.fine(message)
      return
    }
    logger.severe(message)
    alertManager.send(
      messages.getMessage(
        Message.PUNISH_NO_GROUP,
        "check_name",
        configManager.labelCatalog.decorate(checkName, labels),
        "player",
        playerName,
      ),
      AlertType.REGULAR,
    )
  }

  private fun joinForStorage(labels: List<String>): String {
    val kept = StringBuilder()
    for (label in labels) {
      val addition = if (kept.isEmpty()) label else ",$label"
      if (kept.length + addition.length > LABELS_COLUMN_LENGTH) break
      kept.append(addition)
    }
    return kept.toString()
  }

  private fun visible(labels: List<String>): List<String> = labels.filter {
    it.isNotBlank() && !configManager.labelCatalog.hidden(it)
  }

  private fun displayLabels(labels: List<String>): String =
    configManager.labelCatalog.format(visible(labels))

  private fun labelsLine(labels: List<String>): String = messages.labelsLine(displayLabels(labels))

  private companion object {
    const val LABELS_COLUMN_LENGTH = 255
    const val LOCK_STRIPES = 32
  }
}
