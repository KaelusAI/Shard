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
package ac.shard.api.impl

import ac.shard.ai.label.DetectionKey
import ac.shard.alert.AlertManager
import ac.shard.api.ApiVersion
import ac.shard.api.Initiator
import ac.shard.api.Page
import ac.shard.api.Shard
import ac.shard.api.alert.Alert
import ac.shard.api.alert.AlertService
import ac.shard.api.alert.AlertType
import ac.shard.api.detection.DetectionService
import ac.shard.api.detection.Label
import ac.shard.api.detection.LabelId
import ac.shard.api.detection.Model
import ac.shard.api.event.EventBus
import ac.shard.api.event.config.ConfigurationArea
import ac.shard.api.exemption.Exemption
import ac.shard.api.exemption.ExemptionScope
import ac.shard.api.exemption.ExemptionService
import ac.shard.api.history.HistoryService
import ac.shard.api.history.MitigationRecord
import ac.shard.api.history.ViolationRecord
import ac.shard.api.impl.event.BufferResetEventImpl
import ac.shard.api.impl.event.ClientBrandEventImpl
import ac.shard.api.impl.event.ConfigurationChangeEventImpl
import ac.shard.api.impl.event.MitigationChangeEventImpl
import ac.shard.api.impl.event.SessionEndEventImpl
import ac.shard.api.impl.event.SessionStartEventImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.api.impl.event.ViolationLevelResetEventImpl
import ac.shard.api.mitigation.MitigationService
import ac.shard.api.mitigation.MitigationTier
import ac.shard.api.network.NetworkService
import ac.shard.api.network.RemoteSuspect
import ac.shard.api.player.KnownPlayer
import ac.shard.api.player.PlayerService
import ac.shard.api.punishment.PunishmentGroup
import ac.shard.api.punishment.PunishmentService
import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.database.MitigationLogEntry
import ac.shard.database.Violation
import ac.shard.http.ShardHttp
import ac.shard.mitigation.MitigationRuntime
import ac.shard.mitigation.MitigationSkip
import ac.shard.network.NetworkAlertService
import ac.shard.network.NetworkSuspiciousService
import ac.shard.player.ExemptManager
import ac.shard.player.PlayerDataManager
import ac.shard.player.PlayerDirectory
import ac.shard.player.ShardPlayer as InternalPlayer
import ac.shard.punishment.PunishNode
import ac.shard.utils.MiniText
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.Optional
import java.util.SortedSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

private const val MAX_PAGE = 100

internal fun nameOf(server: org.bukkit.Server, database: DatabaseManager?, playerId: UUID): String =
  server.getPlayer(playerId)?.name
    ?: database?.let { runCatching { it.database.findPlayer(playerId)?.name }.getOrNull() }
    ?: server.getOfflinePlayer(playerId).name
    ?: playerId.toString()

class Sessions(
  private val config: ConfigManager,
  private val skip: MitigationSkip,
  private val events: ShardEvents,
) {
  fun of(player: InternalPlayer): SessionView = SessionView(player, config, skip)

  fun started(player: InternalPlayer) {
    synchronized(player.sessionLock) {
      if (player.sessionEnded || player.sessionStarted) return
      player.sessionStarted = true
      val session = of(player)
      events.publish(SessionStartEventImpl(session))
      if (player.brandReceived) events.publish(ClientBrandEventImpl(session, player.brand))
    }
  }

  fun ended(player: InternalPlayer) {
    synchronized(player.sessionLock) {
      player.sessionEnded = true
      if (player.sessionStarted) events.publish(SessionEndEventImpl(of(player)))
    }
  }
}

class PlayerServiceImpl(
  private val players: PlayerDataManager,
  private val directory: PlayerDirectory,
  private val database: DatabaseManager,
  private val tasks: ApiTasks,
  private val sessions: Sessions,
) : PlayerService {
  override fun player(playerId: UUID): Optional<ac.shard.api.player.ShardPlayer> =
    Optional.ofNullable(players.getPlayer(playerId)?.let(sessions::of))

  override fun player(player: Player): Optional<ac.shard.api.player.ShardPlayer> =
    player(player.uniqueId)

  override fun players(): Collection<ac.shard.api.player.ShardPlayer> =
    Collections.unmodifiableList(players.getPlayers().map(sessions::of))

  override fun lookup(nameOrId: String): CompletableFuture<Optional<KnownPlayer>> = tasks.supply {
    Optional.ofNullable(directory.find(nameOrId)?.let(KnownPlayerView::of))
  }

  override fun search(namePrefix: String, limit: Int): CompletableFuture<List<KnownPlayer>> {
    require(limit in 1..MAX_PAGE) { "limit must be 1 to $MAX_PAGE" }
    return tasks.supply {
      database.database.findPlayersByPrefix(namePrefix, limit).map(KnownPlayerView::of)
    }
  }
}

@Suppress("TooManyFunctions")
class DetectionServiceImpl(
  private val config: ConfigManager,
  private val players: PlayerDataManager,
  private val database: DatabaseManager,
  private val tasks: ApiTasks,
  private val events: ShardEvents,
  private val server: org.bukkit.Server,
) : DetectionService {
  override fun models(): List<Model> {
    val profile = config.streamProfile ?: return emptyList()
    val ordered = listOf(profile.primary) + profile.active.filter { it.id != profile.primary.id }
    return Collections.unmodifiableList(
      ordered.mapNotNull { ModelView.of(it, profile, config.settings.localAi, config.labelCatalog) }
    )
  }

  override fun primaryModel(): Optional<Model> =
    Optional.ofNullable(models().firstOrNull { it.isPrimary })

  override fun model(modelId: String): Optional<Model> =
    Optional.ofNullable(models().firstOrNull { it.id() == modelId })

  override fun label(id: LabelId): Optional<Label> =
    model(id.modelId()).flatMap { it.label(id.key()) }

  override fun resetBuffers(initiator: Plugin, playerId: UUID): CompletableFuture<Void?> =
    reset(PluginInitiator(initiator), playerId, null, null) { true }

  override fun resetBuffers(
    initiator: Plugin,
    playerId: UUID,
    modelId: String,
  ): CompletableFuture<Void?> {
    require(config.streamProfile?.model(modelId) != null) { "Unknown model $modelId" }
    return reset(PluginInitiator(initiator), playerId, modelId, null) { it.model == modelId }
  }

  override fun resetBuffer(
    initiator: Plugin,
    playerId: UUID,
    label: LabelId,
  ): CompletableFuture<Void?> =
    reset(PluginInitiator(initiator), playerId, label.modelId(), label) {
      it.model == label.modelId() && it.label == label.key()
    }

  fun reset(
    initiator: Initiator,
    playerId: UUID,
    modelId: String?,
    label: LabelId?,
    match: (DetectionKey) -> Boolean,
  ): CompletableFuture<Void?> {
    tasks.checkOpen()
    val online = players.getPlayer(playerId)
    if (online != null) {
      if (!online.buffersRestored) online.detection.blockRestore(match)
      online.detection.clearWhere(match)
    }
    return tasks.supply<Void?> {
      if (config.settings.buffer.enabled) {
        val primary = config.streamProfile?.primary?.id ?: ""
        database.database.clearAiLabelBuffers(playerId) {
          match(DetectionKey.parseAddress(it) ?: DetectionKey(primary, it))
        }
      }
      events.publish(
        BufferResetEventImpl(
          playerId,
          nameOf(server, database, playerId),
          initiator,
          modelId,
          label,
        )
      )
      null
    }
  }
}

internal class PunishmentGroupView(
  private val node: PunishNode,
  private val parentKey: String?,
  private val fallback: Boolean,
  private val children: List<PunishmentGroup>,
) : PunishmentGroup {
  override fun key(): String = node.key

  override fun name(): String = node.name

  override fun parentKey(): String? = parentKey

  override fun children(): List<PunishmentGroup> = Collections.unmodifiableList(children)

  override fun isFallback(): Boolean = fallback

  override fun expiry(): Duration? = node.expireMillis.takeIf { it > 0 }?.let(Duration::ofMillis)

  override fun actionLevels(): SortedSet<Int> =
    java.util.Collections.unmodifiableSortedSet(java.util.TreeSet(node.actions.keys))

  companion object {
    fun tree(root: PunishNode): List<PunishmentGroup> {
      val out = ArrayList<PunishmentGroup>()
      fun visit(node: PunishNode, parent: PunishNode?, fallback: Boolean): PunishmentGroup {
        val kids = ArrayList<PunishmentGroup>()
        val view =
          PunishmentGroupView(node, parent?.key?.takeIf { parent !== root }, fallback, kids)
        out += view
        node.groups.forEach { kids += visit(it, node, false) }
        node.other?.let { kids += visit(it, node, true) }
        return view
      }
      root.groups.forEach { visit(it, root, false) }
      root.other?.let { visit(it, root, true) }
      return out
    }
  }
}

class PunishmentServiceImpl(
  private val config: ConfigManager,
  private val database: DatabaseManager,
  private val tasks: ApiTasks,
  private val events: ShardEvents,
  private val server: org.bukkit.Server,
) : PunishmentService {
  override fun groups(): List<PunishmentGroup> =
    Collections.unmodifiableList(PunishmentGroupView.tree(config.punishmentTree.root))

  override fun group(key: String): Optional<PunishmentGroup> =
    Optional.ofNullable(groups().firstOrNull { it.key() == key })

  override fun route(label: LabelId): Optional<PunishmentGroup> {
    val singleHead = config.streamProfile?.model(label.modelId())?.singleHead ?: false
    val address = DetectionKey(label.modelId(), label.key()).address()
    val node = config.punishmentTree.route(address, singleHead)
    return Optional.ofNullable(node?.let { group(it.key).orElse(null) })
  }

  override fun violationLevel(playerId: UUID, groupKey: String): CompletableFuture<Int> {
    val windows = expiryWindows()
    return tasks.supply { levelOf(playerId, groupKey, windows) }
  }

  override fun violationLevels(playerId: UUID): CompletableFuture<Map<String, Int>> {
    val windows = expiryWindows()
    return tasks.supply {
      windows.keys.associateWith { levelOf(playerId, it, windows) }.filterValues { it > 0 }
    }
  }

  private fun expiryWindows(): Map<String, Long> =
    config.punishmentTree.root.all().associate { it.key to it.expireMillis }

  private fun levelOf(playerId: UUID, groupKey: String, windows: Map<String, Long>): Int {
    val expire = windows[groupKey] ?: 0L
    val since = if (expire > 0L) System.currentTimeMillis() - expire else 0L
    return database.database.getViolationLevel(playerId, groupKey, since)
  }

  override fun resetViolationLevel(
    initiator: Plugin,
    playerId: UUID,
    groupKey: String,
  ): CompletableFuture<Void?> {
    require(group(groupKey).isPresent) { "Unknown punishment group $groupKey" }
    return reset(PluginInitiator(initiator), playerId, groupKey)
  }

  override fun resetViolationLevels(initiator: Plugin, playerId: UUID): CompletableFuture<Void?> =
    reset(PluginInitiator(initiator), playerId, null)

  fun reset(initiator: Initiator, playerId: UUID, groupKey: String?): CompletableFuture<Void?> =
    tasks.supply<Void?> {
      if (groupKey == null) {
        database.database.resetAllViolationLevels(playerId)
      } else {
        database.database.resetViolationLevel(playerId, groupKey)
      }
      events.publish(
        ViolationLevelResetEventImpl(
          playerId,
          nameOf(server, database, playerId),
          initiator,
          groupKey,
        )
      )
      null
    }
}

@Suppress("LongParameterList")
class MitigationServiceImpl(
  private val runtime: MitigationRuntime,
  private val players: PlayerDataManager,
  private val database: DatabaseManager,
  private val tasks: ApiTasks,
  private val events: ShardEvents,
  private val sessions: Sessions,
) : MitigationService {
  override fun release(initiator: Plugin, playerId: UUID): CompletableFuture<Boolean> {
    tasks.checkOpen()
    val player = players.getPlayer(playerId) ?: return CompletableFuture.completedFuture(false)
    return tasks.onMain { future ->
      val state = player.mitigation
      val rule = state.applied
      if (rule == null || !player.isAttached || player.sessionEnded) {
        future.complete(false)
        return@onMain
      }
      val tier = apiTier(state.appliedTier.name) ?: MitigationTier.NONE
      runtime.clearFor(player)
      events.publish(
        MitigationChangeEventImpl(
          sessions.of(player),
          tier,
          MitigationTier.NONE,
          rule.id,
          null,
          PluginInitiator(initiator),
        )
      )
      future.complete(true)
    }
  }

  override fun clearScore(initiator: Plugin, playerId: UUID): CompletableFuture<Void?> {
    tasks.checkOpen()
    val now = System.currentTimeMillis()
    val player = players.getPlayer(playerId)
    player?.mitigation?.clearScore(now)
    return tasks.supply<Void?> {
      database.database.clearMitigationScore(playerId, now)
      null
    }
  }
}

class ExemptionServiceImpl(private val exempts: ExemptManager) : ExemptionService {
  override fun builder(owner: Plugin, playerId: UUID): Exemption.Builder =
    GrantBuilder(owner, playerId)

  override fun exemptions(playerId: UUID): List<Exemption> = exempts.exemptions(playerId)

  override fun revokeAll(owner: Plugin, playerId: UUID): Int =
    exempts.revokeAll(PluginInitiator(owner), playerId)

  override fun revokeAll(owner: Plugin): Int = exempts.revokeAll(PluginInitiator(owner))

  private inner class GrantBuilder(private val owner: Plugin, private val playerId: UUID) :
    Exemption.Builder {
    private var scope = ExemptionScope.ENFORCEMENT
    private var duration: Duration? = null
    private var reason: String? = null

    override fun scope(scope: ExemptionScope): Exemption.Builder = apply { this.scope = scope }

    override fun duration(duration: Duration): Exemption.Builder = apply {
      require(!duration.isNegative && !duration.isZero) { "Duration must be positive" }
      this.duration = duration
    }

    override fun reason(reason: String): Exemption.Builder = apply { this.reason = reason }

    override fun grant(): Exemption {
      require(owner.isEnabled) { "${owner.name} is not enabled" }
      return exempts.grant(PluginInitiator(owner), playerId, scope, duration, reason)
    }
  }
}

internal class AlertView(
  private val initiator: Initiator,
  private val type: AlertType,
  private val subject: UUID?,
  private val message: String,
  private val network: Boolean,
) : Alert {
  override fun initiator(): Initiator = initiator

  override fun type(): AlertType = type

  override fun subject(): UUID? = subject

  override fun message(): String = message

  override fun isNetwork(): Boolean = network
}

class AlertServiceImpl(private val alerts: AlertManager) : AlertService {
  override fun isSubscribed(staff: Player, type: AlertType): Boolean =
    alerts.hasAlertsEnabled(staff, ac.shard.alert.AlertType.of(type))

  override fun setSubscribed(staff: Player, type: AlertType, subscribed: Boolean) {
    alerts.setEnabled(staff, ac.shard.alert.AlertType.of(type), subscribed)
  }

  override fun builder(initiator: Plugin): Alert.Builder = Builder(PluginInitiator(initiator))

  override fun send(alert: Alert) {
    alerts.send(
      MiniText.deserializeRaw(alert.message()),
      ac.shard.alert.AlertType.of(alert.type()),
      alert.subject(),
      alert.initiator(),
      alert.isNetwork(),
    )
  }

  private class Builder(private val initiator: Initiator) : Alert.Builder {
    private var type = AlertType.CUSTOM
    private var subject: UUID? = null
    private var message: String? = null
    private var network = false

    override fun type(type: AlertType): Alert.Builder = apply { this.type = type }

    override fun subject(playerId: UUID): Alert.Builder = apply { subject = playerId }

    override fun message(miniMessage: String): Alert.Builder = apply { message = miniMessage }

    override fun network(network: Boolean): Alert.Builder = apply { this.network = network }

    override fun build(): Alert {
      val text = checkNotNull(message) { "An alert needs a message" }
      return AlertView(initiator, type, subject, text, network)
    }
  }
}

internal class ViolationRecordView(private val violation: Violation, private val primary: String) :
  ViolationRecord {
  override fun server(): String = violation.serverName

  override fun playerId(): UUID = violation.playerUUID

  override fun playerName(): String = violation.playerName

  override fun createdAt(): Instant = violation.createdAt

  override fun violationLevel(): Int = violation.vl

  override fun labels(): Set<LabelId> =
    Collections.unmodifiableSet(
      violation.labels.split(',').map(String::trim).filter(String::isNotEmpty).mapNotNullTo(
        mutableSetOf()
      ) {
        labelIdOfAddress(it, primary)
      }
    )

  override fun buffer(): Double = violation.aiBuffer ?: 0.0

  override fun mitigationScore(): Double = violation.mitigationScore ?: 0.0
}

internal class MitigationRecordView(
  private val entry: MitigationLogEntry,
  private val playerId: UUID,
) : MitigationRecord {
  override fun server(): String = entry.serverName

  override fun playerId(): UUID = playerId

  override fun ruleId(): String = entry.rule

  override fun tier(): MitigationTier = apiTier(entry.tier) ?: MitigationTier.NONE

  override fun score(): Double = entry.score

  override fun startedAt(): Instant = Instant.ofEpochMilli(entry.startedAt)

  override fun endedAt(): Instant? = entry.endedAt.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
}

internal class PageView<T : Any>(
  private val items: List<T>,
  private val page: Int,
  private val pageSize: Int,
  private val totalItems: Long,
) : Page<T> {
  override fun items(): List<T> = Collections.unmodifiableList(items)

  override fun page(): Int = page

  override fun pageSize(): Int = pageSize

  override fun totalItems(): Long = totalItems
}

class HistoryServiceImpl(
  private val config: ConfigManager,
  private val database: DatabaseManager,
  private val tasks: ApiTasks,
) : HistoryService {
  override fun violations(
    playerId: UUID,
    page: Int,
    pageSize: Int,
  ): CompletableFuture<Page<ViolationRecord>> {
    checkPage(page, pageSize)
    return tasks.supply {
      val db = database.database
      PageView(
        db.getViolations(playerId, storagePage(page), pageSize).map(::record),
        page,
        pageSize,
        db.getLogCount(playerId).toLong(),
      )
    }
  }

  override fun violations(
    since: Instant,
    page: Int,
    pageSize: Int,
  ): CompletableFuture<Page<ViolationRecord>> {
    checkPage(page, pageSize)
    val from = since.coerceIn(EARLIEST, LATEST).toEpochMilli()
    return tasks.supply {
      val db = database.database
      PageView(
        db.getViolations(storagePage(page), pageSize, from).map(::record),
        page,
        pageSize,
        db.getLogCount(from).toLong(),
      )
    }
  }

  override fun mitigations(playerId: UUID, limit: Int): CompletableFuture<List<MitigationRecord>> {
    require(limit in 1..MAX_PAGE) { "limit must be 1 to $MAX_PAGE" }
    return tasks.supply {
      database.database.getMitigationLog(playerId, limit).map { MitigationRecordView(it, playerId) }
    }
  }

  override fun mitigations(limit: Int): CompletableFuture<List<MitigationRecord>> {
    require(limit in 1..MAX_PAGE) { "limit must be 1 to $MAX_PAGE" }
    return tasks.supply {
      database.database.getMitigationLog(limit).mapNotNull { entry ->
        entry.playerUUID?.let { MitigationRecordView(entry, it) }
      }
    }
  }

  private fun record(violation: Violation): ViolationRecord =
    ViolationRecordView(violation, config.streamProfile?.primary?.id ?: "")

  private fun checkPage(page: Int, pageSize: Int) {
    require(page >= 0) { "page must not be negative" }
    require(pageSize in 1..MAX_PAGE) { "pageSize must be 1 to $MAX_PAGE" }
  }

  private fun storagePage(page: Int): Int = page.coerceAtMost(Int.MAX_VALUE - 1) + 1

  private companion object {
    val EARLIEST: Instant = Instant.ofEpochMilli(Long.MIN_VALUE)
    val LATEST: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)
  }
}

internal class RemoteSuspectView(private val snapshot: ac.shard.network.SuspiciousSnapshot) :
  RemoteSuspect {
  override fun server(): String = snapshot.server

  override fun playerId(): UUID = UUID.fromString(snapshot.uuid)

  override fun playerName(): String = snapshot.name

  override fun buffer(): Double = snapshot.buffer

  override fun ping(): Int = snapshot.ping

  override fun tier(): MitigationTier? = apiTier(snapshot.level)

  override fun score(): Double = snapshot.score

  override fun updatedAt(): Instant = Instant.ofEpochMilli(snapshot.updatedAt)
}

class NetworkServiceImpl(
  private val alerts: NetworkAlertService,
  private val suspicious: NetworkSuspiciousService,
  private val tasks: ApiTasks,
) : NetworkService {
  override fun isEnabled(): Boolean = alerts.isEnabled

  override fun serverName(): String = alerts.name

  override fun suspects(): CompletableFuture<List<RemoteSuspect>> = tasks.supply {
    suspicious.fetchRemote().mapNotNull { snapshot ->
      runCatching { UUID.fromString(snapshot.uuid) }
        .getOrNull()
        ?.let { RemoteSuspectView(snapshot) }
    }
  }
}

@Suppress("LongParameterList", "TooManyFunctions")
class ShardImpl(
  private val http: ShardHttp,
  private val players: PlayerService,
  private val detection: DetectionService,
  private val punishments: PunishmentService,
  private val mitigation: MitigationService,
  private val exemptions: ExemptionService,
  private val alerts: AlertService,
  private val history: HistoryService,
  private val network: NetworkService,
  private val events: ShardEvents,
  private val tasks: ApiTasks,
  config: ConfigManager,
) : Shard {
  init {
    config.profiles.addListener { _, _ ->
      events.publish(ConfigurationChangeEventImpl(setOf(ConfigurationArea.MODELS)))
    }
  }

  fun close() {
    tasks.close()
  }

  private fun <T> open(value: T): T {
    tasks.checkOpen()
    return value
  }

  override fun apiVersion(): ApiVersion =
    open(ApiVersion(ApiVersion.COMPILED_MAJOR, ApiVersion.COMPILED_MINOR, 0))

  override fun pluginVersion(): String = open(http.pluginVersion)

  override fun players(): PlayerService = open(players)

  override fun detection(): DetectionService = open(detection)

  override fun punishments(): PunishmentService = open(punishments)

  override fun mitigation(): MitigationService = open(mitigation)

  override fun exemptions(): ExemptionService = open(exemptions)

  override fun alerts(): AlertService = open(alerts)

  override fun history(): HistoryService = open(history)

  override fun network(): NetworkService = open(network)

  override fun events(): EventBus = open(events)
}
