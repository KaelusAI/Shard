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
package ac.shard.punishment.cloud

import ac.shard.Shard
import ac.shard.api.event.config.ConfigurationArea
import ac.shard.api.impl.event.ConfigurationChangeEventImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.config.CloudPunishments
import ac.shard.config.ConfigManager
import ac.shard.config.ShardSettings
import ac.shard.http.HttpBodies
import ac.shard.http.HttpOutcome
import ac.shard.http.Json
import ac.shard.http.ShardHttp
import ac.shard.http.isSecureEndpoint
import ac.shard.punishment.PunishmentTreeParser
import ac.shard.utils.AtomicFiles
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

@Suppress("TooGenericExceptionCaught", "TooManyFunctions", "ReturnCount")
class PunishmentSync(
  private val plugin: Shard,
  private val http: ShardHttp,
  private val configManager: ConfigManager,
  private val events: ShardEvents,
) {
  private val mapper = Json.mapper
  private val client = http.client
  private val running = AtomicBoolean(false)

  @Volatile private var state: State? = null
  @Volatile private var rejectedRev = -1L
  @Volatile private var refusedHash: String? = null
  @Volatile private var settings: ShardSettings = ShardSettings.DEFAULT

  @Volatile
  var status: Status = Status()
    private set

  data class State(val rev: Long, val base: ObjectNode)

  data class Status(
    val rev: Long = 0L,
    val at: Long = 0L,
    val error: String? = null,
    val kept: List<String> = emptyList(),
  )

  private data class Local(val doc: ObjectNode, val commands: List<String>)

  private data class Remote(val rev: Long, val tree: ObjectNode)

  private sealed interface Put {
    data class Saved(val rev: Long) : Put

    data class Conflict(val remote: Remote) : Put

    data class Refused(val reasons: List<String>) : Put

    data object Failed : Put
  }

  fun beat(): Map<String, Any>? {
    val mode = configManager.settings.telemetry.cloudPunishments
    if (mode == CloudPunishments.OFF) return null
    val local = readLocal() ?: return null
    return mapOf("rev" to state().rev, "hash" to PunishmentDoc.hash(local.doc), "mode" to mode.wire)
  }

  fun onBeat(cloudRev: Long) {
    val snapshot = configManager.settings
    val mode = snapshot.telemetry.cloudPunishments
    if (mode == CloudPunishments.OFF || !running.compareAndSet(false, true)) return
    settings = snapshot
    try {
      cycle(mode, cloudRev)
    } catch (e: Exception) {
      fail(e.message ?: e.javaClass.simpleName)
    } finally {
      running.set(false)
    }
  }

  private fun cycle(mode: CloudPunishments, cloudRev: Long) {
    if (panelUrl() == null) return
    val local = readLocal() ?: return
    val known = state()
    val changed = !TreeMerge.same(local.doc, known.base) && !refused(local.doc)
    if (mode == CloudPunishments.UPLOAD) {
      if (known.rev == 0L || cloudRev != known.rev || changed) {
        commit(mode, known.rev, known.base, local.doc, local)
      }
      return
    }
    when {
      known.rev == 0L -> firstSync(mode, local)
      cloudRev != known.rev -> pull(mode, known, local)
      changed -> commit(mode, known.rev, known.base, local.doc, local)
    }
  }

  private fun refused(doc: ObjectNode): Boolean = refusedHash == PunishmentDoc.hash(doc)

  private fun firstSync(mode: CloudPunishments, local: Local) {
    val remote = fetch(0L)
    if (remote.rev == 0L) {
      commit(mode, 0L, PunishmentDoc.empty(), local.doc, local)
      return
    }
    if (!accepted(remote)) return
    val merged =
      if (local.doc == shippedDoc()) {
        remote.tree
      } else {
        TreeMerge.merge(PunishmentDoc.empty(), local.doc, remote.tree).also(::noteKept).tree
      }
    if (!write(merged, local)) return
    save(remote.rev, remote.tree)
    if (!TreeMerge.same(merged, remote.tree)) commit(mode, remote.rev, remote.tree, merged, local)
  }

  private fun pull(mode: CloudPunishments, known: State, local: Local) {
    val remote = fetch(known.rev)
    if (remote.rev == 0L) {
      commit(mode, 0L, PunishmentDoc.empty(), local.doc, local)
      return
    }
    if (remote.rev == known.rev) {
      if (!TreeMerge.same(local.doc, known.base) && !refused(local.doc)) {
        commit(mode, known.rev, known.base, local.doc, local)
      }
      return
    }
    if (!accepted(remote)) return
    val merged = TreeMerge.merge(known.base, local.doc, remote.tree).also(::noteKept).tree
    if (!write(merged, local)) return
    save(remote.rev, remote.tree)
    if (!TreeMerge.same(merged, remote.tree)) commit(mode, remote.rev, remote.tree, merged, local)
  }

  private fun commit(
    mode: CloudPunishments,
    startRev: Long,
    startBase: ObjectNode,
    startTree: ObjectNode,
    local: Local,
  ) {
    var rev = startRev
    var base = startBase
    var tree = startTree
    repeat(MAX_ATTEMPTS) {
      when (val result = put(rev, tree, local.commands, mode)) {
        is Put.Saved -> {
          refusedHash = null
          save(result.rev, tree)
          return
        }
        is Put.Refused -> {
          refusedHash = PunishmentDoc.hash(tree)
          fail("the panel did not accept punishments.yml: ${result.reasons.joinToString("; ")}")
          return
        }
        Put.Failed -> return
        is Put.Conflict -> {
          val remote = result.remote
          if (mode == CloudPunishments.UPLOAD || remote.rev == 0L) {
            rev = remote.rev
            if (remote.rev == 0L) base = PunishmentDoc.empty()
          } else {
            if (!accepted(remote)) return
            val merged = TreeMerge.merge(base, tree, remote.tree).also(::noteKept).tree
            if (!write(merged, local)) return
            save(remote.rev, remote.tree)
            if (TreeMerge.same(merged, remote.tree)) return
            rev = remote.rev
            base = remote.tree
            tree = merged
          }
        }
      }
    }
  }

  private fun accepted(remote: Remote): Boolean {
    val problems = PunishmentDoc.problems(remote.tree)
    if (problems.isEmpty()) return true
    if (rejectedRev != remote.rev) {
      rejectedRev = remote.rev
      plugin.logger.warning(
        "[Punish] cloud tree rev ${remote.rev} rejected: ${problems.take(MAX_REASONS).joinToString("; ")}"
      )
    }
    status = status.copy(error = "changes from the panel were not applied, they contain errors")
    return false
  }

  private fun noteKept(result: TreeMerge.Result) {
    status = status.copy(kept = result.conflicts)
  }

  private fun write(merged: ObjectNode, local: Local): Boolean {
    if (TreeMerge.same(merged, local.doc)) return true
    val file = configManager.punishmentsFile()
    val current = file.readText()
    val onDisk =
      runCatching { PunishmentDoc.normalize(PunishmentDoc.of(parse(current))) }.getOrNull()
    if (!TreeMerge.same(onDisk, local.doc)) {
      fail("punishments.yml changed during sync, will try again")
      return false
    }
    val text = PunishmentsFile.withTree(current, merged)
    val check = runCatching { PunishmentDoc.normalize(PunishmentDoc.of(parse(text))) }.getOrNull()
    if (!TreeMerge.same(check, merged)) {
      fail("could not save changes from the panel into punishments.yml")
      return false
    }
    val dir = syncDir()
    file.copyTo(File(dir, PREVIOUS_FILE), overwrite = true)
    AtomicFiles.replace(file.toPath()) { Files.writeString(it, text) }
    configManager.reloadPunishments()
    events.publish(ConfigurationChangeEventImpl(setOf(ConfigurationArea.PUNISHMENT_GROUPS)))
    return true
  }

  private fun fetch(have: Long): Remote {
    val url = panelUrl() ?: error("no panel address")
    val response =
      client.send(
        request("$url?have=$have").GET().build(),
        HttpBodies.text(HttpBodies.PANEL_LIMIT),
      )
    val status = response.statusCode()
    return when {
      status == HTTP_NOT_MODIFIED -> Remote(have, state().base)
      HttpOutcome.of(status) == HttpOutcome.OK ->
        remoteOf(mapper.readTree(response.body())) ?: error("panel sent no tree")
      else -> error(refusal(status))
    }
  }

  private fun put(
    base: Long,
    tree: ObjectNode,
    commands: List<String>,
    mode: CloudPunishments,
  ): Put {
    val url = panelUrl() ?: return Put.Failed
    val body =
      mapper.writeValueAsString(
        mapOf(
          "base" to base,
          "tree" to tree,
          "hash" to PunishmentDoc.hash(tree),
          "commands" to commands,
          "mode" to mode.wire,
        )
      )
    val response =
      client.send(
        request(url)
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpBodies.text(HttpBodies.PANEL_LIMIT),
      )
    val root = runCatching { mapper.readTree(response.body()) }.getOrNull()
    val status = response.statusCode()
    if (status == HTTP_UNPROCESSABLE) {
      return Put.Refused(root?.path("reasons")?.values()?.map { it.asString("") }.orEmpty())
    }
    return when (HttpOutcome.of(status)) {
      HttpOutcome.OK ->
        root?.path("rev")?.takeIf { it.canConvertToLong() }?.let { Put.Saved(it.asLong(0L)) }
          ?: Put.Failed.also { fail("panel did not return a revision") }
      HttpOutcome.CONFLICT -> root?.let(::remoteOf)?.let { Put.Conflict(it) } ?: Put.Failed
      else -> Put.Failed.also { fail(refusal(status)) }
    }
  }

  private fun refusal(code: Int): String =
    when (HttpOutcome.of(code)) {
      HttpOutcome.REJECTED ->
        "the panel did not accept this server: the key is unknown or this server's IP is " +
          "not on its allowlist"
      HttpOutcome.RATE_LIMITED -> "too many requests to the panel, will try again later"
      HttpOutcome.UNAVAILABLE -> "the panel is unavailable, will try again later"
      else -> "the panel returned an error ($code)"
    }

  private fun remoteOf(root: JsonNode): Remote? {
    val rev = root.path("rev").takeIf { it.canConvertToLong() }?.asLong(0L) ?: return null
    if (rev == 0L) return Remote(0L, PunishmentDoc.empty())
    val tree = root.get("tree") as? ObjectNode ?: return null
    val usable = PunishmentDoc.problems(tree).isEmpty()
    return Remote(rev, if (usable) PunishmentDoc.normalize(tree) else tree)
  }

  private fun panelUrl(): String? {
    val base = settings.panelUrl.trim().trimEnd('/')
    return if (base.isBlank() || !isSecureEndpoint(base)) null else base + PATH
  }

  private fun request(url: String): HttpRequest.Builder =
    http
      .keyed(URI.create(url), settings.ai.key)
      .header("Accept", "application/json")
      .timeout(REQUEST_TIMEOUT)

  private fun readLocal(): Local? {
    val file = configManager.punishmentsFile()
    if (!file.isFile) return null
    val root = runCatching { parse(file.readText()) }.getOrNull() ?: return null
    if (!PunishmentTreeParser.isCurrent(root)) return null
    return Local(PunishmentDoc.normalize(PunishmentDoc.of(root)), PunishmentDoc.commandNames(root))
  }

  private fun shippedDoc(): ObjectNode? =
    javaClass.classLoader.getResourceAsStream(SHIPPED)?.use { stream ->
      PunishmentDoc.of(parse(stream.bufferedReader().readText()))
    }

  private fun parse(text: String): ConfigurationNode =
    YamlConfigurationLoader.builder().source { text.reader().buffered() }.build().load()

  private fun state(): State {
    state?.let {
      return it
    }
    val file = File(syncDir(), STATE_FILE)
    val loaded =
      runCatching {
          val root = mapper.readTree(file)
          State(
            root.path("rev").asLong(0L),
            PunishmentDoc.normalize(root.get("base") as? ObjectNode ?: PunishmentDoc.empty()),
          )
        }
        .getOrNull() ?: State(0L, PunishmentDoc.empty())
    state = loaded
    if (loaded.rev > 0L) status = status.copy(rev = loaded.rev, at = file.lastModified())
    return loaded
  }

  private fun save(rev: Long, base: ObjectNode) {
    val next = State(rev, base)
    state = next
    AtomicFiles.replace(File(syncDir(), STATE_FILE).toPath()) {
      mapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(it.toFile(), mapOf("rev" to rev, "base" to base))
    }
    status = status.copy(rev = rev, at = System.currentTimeMillis(), error = null)
  }

  private fun fail(message: String) {
    status = status.copy(error = message)
    plugin.logger.fine("[Punish] cloud sync: $message")
  }

  private fun syncDir(): File = File(plugin.dataFolder, SYNC_DIR).also { it.mkdirs() }

  private companion object {
    const val PATH = "/api/v1/device/punishments"
    const val SHIPPED = "punishments.yml"
    const val SYNC_DIR = "sync"
    const val STATE_FILE = "punishments.json"
    const val PREVIOUS_FILE = "punishments.previous.yml"
    const val MAX_ATTEMPTS = 3
    const val MAX_REASONS = 3
    const val HTTP_NOT_MODIFIED = 304
    const val HTTP_UNPROCESSABLE = 422
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
  }
}
