/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
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
import ac.shard.alert.AlertManager
import ac.shard.alert.AlertType
import ac.shard.api.event.PunishmentTriggeredEvent
import ac.shard.checks.ICheck
import ac.shard.config.ConfigManager
import ac.shard.coroutines.ShardCoroutines
import ac.shard.database.DatabaseManager
import ac.shard.database.ViolationDatabase
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.util.Locale
import java.util.NavigableMap
import java.util.TreeMap
import kotlin.coroutines.resume
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.serialize.SerializationException

class PunishmentManager(
  private val shardPlayer: ShardPlayer,
  private val plugin: Shard,
  private val configManager: ConfigManager,
  databaseManager: DatabaseManager,
  private val alertManager: AlertManager,
  private val adventure: BukkitAudiences,
  private val scheduler: SchedulerService,
  private val coroutines: ShardCoroutines,
) {
  private val punishmentGroups = HashMap<String, PunishGroup>()
  private val database: ViolationDatabase = databaseManager.database

  init {
    reload()
  }

  fun interface Factory {
    fun create(shardPlayer: ShardPlayer): PunishmentManager
  }

  fun reload() {
    punishmentGroups.clear()

    val punishmentsSection = configManager.punishments.node("Punishments")
    if (punishmentsSection.empty()) {
      return
    }

    for (groupEntry in punishmentsSection.childrenMap().entries) {
      val groupName = groupEntry.key.toString()
      val groupSection = groupEntry.value

      val checkNamesFilters = getStringList(groupSection.node("checks"))
      val actionsSection = groupSection.node("actions")
      if (actionsSection.empty()) {
        continue
      }

      val parsedActions: NavigableMap<Int, List<String>> = TreeMap()
      for (actionEntry in actionsSection.childrenMap().entries) {
        val vlString = actionEntry.key.toString()
        try {
          val vl = vlString.toInt()
          val commands = getStringList(actionEntry.value)
          parsedActions[vl] = commands
        } catch (e: NumberFormatException) {
          plugin.logger.warning("Invalid VL $vlString in punishment group $groupName.")
        }
      }

      if (parsedActions.isNotEmpty()) {
        val punishGroup = PunishGroup(groupName, checkNamesFilters, parsedActions)
        punishmentGroups[groupName] = punishGroup
      }
    }
  }

  private fun getStringList(node: ConfigurationNode): List<String> {
    return try {
      node.getList(String::class.java) ?: emptyList()
    } catch (_: SerializationException) {
      emptyList()
    }
  }

  fun handleFlag(check: ICheck, debug: String) {
    if (
      shardPlayer.exemptManager.isExempt(shardPlayer.player) ||
        shardPlayer.exemptManager.isDisabled(shardPlayer.player)
    ) {
      return
    }

    val playerName = shardPlayer.player.name
    val checkName = check.checkName

    for (group in punishmentGroups.values) {
      if (group.isCheckAssociated(check)) {
        coroutines.scope.launch(coroutines.async) {
          val newVl = database.incrementViolationLevel(shardPlayer.uuid, group.groupName)
          val entry = group.actions.floorEntry(newVl) ?: return@launch
          try {
            executeCommands(group, newVl, debug, entry.value, playerName, checkName)
          } catch (e: Exception) {
            plugin.logger.warning("Failed to execute punishment actions: ${e.message}")
          }
        }
      }
    }
  }

  private suspend fun executeCommands(
    group: PunishGroup,
    vl: Int,
    verbose: String,
    commands: List<String>,
    playerName: String,
    checkName: String,
  ) {
    val event =
      PunishmentTriggeredEvent(
        shardPlayer.uuid,
        playerName,
        checkName,
        group.groupName,
        vl,
        commands.toImmutableList(),
        verbose,
      )
    shardPlayer.eventBus.post(event)
    if (event.cancelled) {
      return
    }
    for (command in commands) {
      executeCommand(group, vl, verbose, command, playerName, checkName)
    }
  }

  private suspend fun executeCommand(
    group: PunishGroup,
    vl: Int,
    verbose: String,
    command: String,
    playerName: String,
    checkName: String,
  ) {
    val trimmed = command.trim()
    val lower = trimmed.lowercase(Locale.ROOT)

    if (lower == "[alert]") {
      runSync { sendAlert(playerName, checkName, vl, verbose) }
      return
    }
    if (lower == "[log]") {
      runAsync { database.logAlert(shardPlayer, verbose, checkName, vl) }
      return
    }
    if (lower == "[reset]") {
      runAsync { database.resetViolationLevel(shardPlayer.uuid, group.groupName) }
      return
    }
    if (lower.startsWith("[broadcast] ")) {
      val message = trimmed.substring("[broadcast] ".length)
      val component: Component =
        MessageUtil.format(
          message,
          "player",
          playerName,
          "uuid",
          shardPlayer.uuid.toString(),
          "check_name",
          checkName,
          "vl",
          vl.toString(),
          "verbose",
          verbose,
        )
      runSync { adventure.players().sendMessage(component) }
      return
    }
    if (lower.startsWith("[wait]")) {
      val argument = extractArgument(trimmed, "[wait]".length)
      handleWait(argument)
      return
    }

    val formattedCmd =
      trimmed
        .replace("<player>", playerName)
        .replace("<uuid>", shardPlayer.uuid.toString())
        .replace("<check_name>", checkName)
        .replace("<vl>", vl.toString())
        .replace("<verbose>", verbose)

    runSync { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formattedCmd) }
  }

  private suspend fun handleWait(argument: String?) {
    if (argument.isNullOrBlank()) {
      return
    }
    val ticks = parseDurationTicks(argument.trim())
    if (ticks <= 0) {
      return
    }
    runLater(ticks)
  }

  private suspend fun runSync(block: () -> Unit) {
    withContext(coroutines.main) { block() }
  }

  private suspend fun runLater(ticks: Long) {
    suspendCancellableCoroutine { continuation ->
      scheduler.runLater(
        {
          if (continuation.isActive) {
            continuation.resume(Unit)
          }
        },
        ticks,
      )
    }
  }

  private suspend fun runAsync(block: () -> Unit) {
    withContext(coroutines.async) { block() }
  }

  private fun extractArgument(input: String, prefixLength: Int): String {
    if (input.length <= prefixLength) {
      return ""
    }
    var rest = input.substring(prefixLength).trim()
    if (rest.startsWith("]")) {
      rest = rest.substring(1).trim()
    }
    return rest
  }

  private fun parseDurationTicks(raw: String): Long {
    val value = raw.trim().lowercase(Locale.ROOT)
    return try {
      when {
        value.endsWith("ms") -> {
          val ms = value.substring(0, value.length - 2).toDouble()
          kotlin.math.max(0L, kotlin.math.ceil(ms / 50.0).toLong())
        }
        value.endsWith("s") -> {
          val seconds = value.substring(0, value.length - 1).toDouble()
          kotlin.math.max(0L, kotlin.math.round(seconds * 20.0).toLong())
        }
        value.endsWith("t") -> {
          val ticks = value.substring(0, value.length - 1).toDouble()
          kotlin.math.max(0L, kotlin.math.round(ticks).toLong())
        }
        else -> {
          val ticks = value.toDouble()
          kotlin.math.max(0L, kotlin.math.round(ticks).toLong())
        }
      }
    } catch (_: NumberFormatException) {
      0L
    }
  }

  private fun sendAlert(playerName: String, checkName: String, vl: Int, verbose: String) {
    val message =
      MessageUtil.getMessage(
        Message.ALERTS_FORMAT,
        "player",
        playerName,
        "check_name",
        checkName,
        "vl",
        vl.toString(),
        "verbose",
        verbose,
      )

    alertManager.send(message, AlertType.REGULAR)
  }
}
