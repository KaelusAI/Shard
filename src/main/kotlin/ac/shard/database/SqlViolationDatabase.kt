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
package ac.shard.database

import ac.shard.config.ConfigManager
import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorTheme
import ac.shard.player.ShardPlayer
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.datetime.InstantColumnType
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

private const val TIER_LENGTH = 16
private const val TEXT_LENGTH = 255
private const val UUID_LENGTH = 36
private const val NAME_LENGTH = 64
private const val TRAIL_BYTES = 1024

class SqlViolationDatabase(
  private val configManager: ConfigManager,
  private val database: Database,
) : ViolationDatabase {

  override fun logAlert(
    player: ShardPlayer,
    verbose: String,
    checkName: String,
    vls: Int,
    facts: AiFacts,
  ) {
    transaction(database) {
      val now = Instant.now()
      Violations.insert {
        it[server] = configManager.config.getString("history.server-name", "server")
        it[uuid] = player.uuid.toString()
        it[playerName] = player.player.name
        it[Violations.checkName] = checkName
        it[Violations.verbose] = verbose
        it[vl] = vls
        it[createdAt] = now.toEpochMilli()
        it[createdAtInstant] = now
        it[aiBuffer] = facts.buffer
        it[mitigationScore] = facts.score
        it[aiWindows] = facts.windows
        it[aiHighWindows] = facts.highWindows
        it[probabilityTrail] = facts.trail.takeIf { trail -> trail.isNotEmpty() }
      }
    }
  }

  override fun getLogCount(player: UUID): Int {
    return transaction(database) {
      Violations.selectAll().where { Violations.uuid eq player.toString() }.count().toInt()
    }
  }

  override fun getViolations(player: UUID, page: Int, limit: Int): List<Violation> {
    return transaction(database) {
      Violations.select(VIOLATION_READ_COLUMNS)
        .where { Violations.uuid eq player.toString() }
        .orderBy(Violations.createdAt to SortOrder.DESC)
        .limit(limit)
        .offset(((page - 1) * limit).toLong())
        .map(::toViolation)
    }
  }

  override fun saveAiSnapshot(playerUUID: UUID, snapshot: AiSnapshot) {
    transaction(database) {
      val uuidString = playerUUID.toString()
      val updated =
        PlayerLogins.update({ PlayerLogins.uuid eq uuidString }) { writeSnapshot(it, snapshot) }
      if (updated > 0) return@transaction
      try {
        PlayerLogins.insert {
          it[uuid] = uuidString
          it[lastSeen] = snapshot.savedAt
          writeSnapshot(it, snapshot)
        }
      } catch (_: java.sql.SQLException) {
        PlayerLogins.update({ PlayerLogins.uuid eq uuidString }) { writeSnapshot(it, snapshot) }
      }
    }
  }

  private fun writeSnapshot(statement: UpdateBuilder<*>, snapshot: AiSnapshot) {
    statement[PlayerLogins.aiHighWindows] = snapshot.highWindows
    statement[PlayerLogins.aiWindows] = snapshot.windows
    statement[PlayerLogins.probLow] = snapshot.low
    statement[PlayerLogins.probMid] = snapshot.middle
    statement[PlayerLogins.probHigh] = snapshot.high
    statement[PlayerLogins.aiStateAt] = snapshot.savedAt
  }

  override fun loadAiSnapshot(playerUUID: UUID): AiSnapshot? {
    return transaction(database) {
      PlayerLogins.select(
          PlayerLogins.aiHighWindows,
          PlayerLogins.aiWindows,
          PlayerLogins.probLow,
          PlayerLogins.probMid,
          PlayerLogins.probHigh,
          PlayerLogins.aiStateAt,
        )
        .where { PlayerLogins.uuid eq playerUUID.toString() }
        .firstOrNull()
        ?.let { row ->
          AiSnapshot(
            highWindows = row[PlayerLogins.aiHighWindows],
            windows = row[PlayerLogins.aiWindows],
            low = row[PlayerLogins.probLow],
            middle = row[PlayerLogins.probMid],
            high = row[PlayerLogins.probHigh],
            savedAt = row[PlayerLogins.aiStateAt],
          )
        }
        ?.takeIf { it.savedAt != 0L }
    }
  }

  override fun recordMitigation(playerUUID: UUID, entry: MitigationLogEntry) {
    transaction(database) {
      MitigationEvents.insert {
        it[server] = entry.serverName
        it[uuid] = playerUUID.toString()
        it[playerName] = entry.playerName
        it[rule] = entry.rule
        it[tier] = entry.tier
        it[score] = entry.score
        it[startedAt] = entry.startedAt
        it[endedAt] = entry.endedAt
      }
    }
  }

  override fun getMitigationLog(playerUUID: UUID, limit: Int): List<MitigationLogEntry> {
    return transaction(database) {
      MitigationEvents.select(
          MitigationEvents.server,
          MitigationEvents.playerName,
          MitigationEvents.rule,
          MitigationEvents.tier,
          MitigationEvents.score,
          MitigationEvents.startedAt,
          MitigationEvents.endedAt,
        )
        .where { MitigationEvents.uuid eq playerUUID.toString() }
        .orderBy(MitigationEvents.endedAt to SortOrder.DESC)
        .limit(limit)
        .map { row ->
          MitigationLogEntry(
            serverName = row[MitigationEvents.server],
            playerName = row[MitigationEvents.playerName],
            rule = row[MitigationEvents.rule],
            tier = row[MitigationEvents.tier],
            score = row[MitigationEvents.score],
            startedAt = row[MitigationEvents.startedAt],
            endedAt = row[MitigationEvents.endedAt],
          )
        }
    }
  }

  override fun getMitigationLog(limit: Int): List<MitigationLogEntry> {
    return transaction(database) {
      MitigationEvents.selectAll()
        .orderBy(MitigationEvents.endedAt to SortOrder.DESC)
        .limit(limit)
        .map { row ->
          MitigationLogEntry(
            serverName = row[MitigationEvents.server],
            playerName = row[MitigationEvents.playerName],
            rule = row[MitigationEvents.rule],
            tier = row[MitigationEvents.tier],
            score = row[MitigationEvents.score],
            startedAt = row[MitigationEvents.startedAt],
            endedAt = row[MitigationEvents.endedAt],
          )
        }
    }
  }

  override fun getLogCount(since: Long): Int {
    return transaction(database) {
      val totalViolations = Violations.id.count()
      val query =
        if (since > 0) {
          Violations.select(totalViolations).where { Violations.createdAt greaterEq since }
        } else {
          Violations.select(totalViolations)
        }
      query.firstOrNull()?.get(totalViolations)?.toInt() ?: 0
    }
  }

  override fun getLogCounts(playerUUIDs: Collection<UUID>): Map<UUID, Int> {
    if (playerUUIDs.isEmpty()) {
      return emptyMap()
    }

    return transaction(database) {
      val uuidCounts = Violations.id.count()
      Violations.select(Violations.uuid, uuidCounts)
        .where { Violations.uuid inList playerUUIDs.map(UUID::toString) }
        .groupBy(Violations.uuid)
        .associate { row -> UUID.fromString(row[Violations.uuid]) to row[uuidCounts].toInt() }
    }
  }

  override fun getViolations(page: Int, limit: Int, since: Long): List<Violation> {
    return transaction(database) {
      val query =
        if (since > 0) {
          Violations.select(VIOLATION_READ_COLUMNS).where { Violations.createdAt greaterEq since }
        } else {
          Violations.select(VIOLATION_READ_COLUMNS)
        }
      query
        .orderBy(Violations.createdAt to SortOrder.DESC)
        .limit(limit)
        .offset(((page - 1) * limit).toLong())
        .map(::toViolation)
    }
  }

  override fun getViolationLevel(playerUUID: UUID, punishGroupName: String): Int {
    return transaction(database) {
      Punishments.selectAll()
        .where {
          (Punishments.uuid eq playerUUID.toString()) and
            (Punishments.punishGroup eq punishGroupName)
        }
        .firstOrNull()
        ?.get(Punishments.vl) ?: 0
    }
  }

  override fun incrementViolationLevel(playerUUID: UUID, punishGroupName: String): Int {
    return transaction(database) {
      Punishments.upsert(onUpdate = { it[Punishments.vl] = Punishments.vl + 1 }) {
        it[uuid] = playerUUID.toString()
        it[punishGroup] = punishGroupName
        it[vl] = 1
      }

      Punishments.selectAll()
        .where {
          (Punishments.uuid eq playerUUID.toString()) and
            (Punishments.punishGroup eq punishGroupName)
        }
        .firstOrNull()
        ?.get(Punishments.vl) ?: 0
    }
  }

  override fun getUniqueViolatorsSince(since: Long): Int {
    return transaction(database) {
      Violations.select(Violations.uuid.countDistinct())
        .where { Violations.createdAt greaterEq since }
        .firstOrNull()
        ?.get(Violations.uuid.countDistinct())
        ?.toInt() ?: 0
    }
  }

  override fun recordLogin(playerUUID: UUID, timestamp: Long) {
    transaction(database) {
      PlayerLogins.upsert(onUpdate = { it[PlayerLogins.lastSeen] = timestamp }) {
        it[uuid] = playerUUID.toString()
        it[lastSeen] = timestamp
      }
    }
  }

  override fun recordAttack(playerUUID: UUID, timestamp: Long) {
    transaction(database) {
      PlayerLogins.upsert(
        onUpdate = {
          it[PlayerLogins.lastSeen] = timestamp
          it[PlayerLogins.lastAttack] = timestamp
        }
      ) {
        it[uuid] = playerUUID.toString()
        it[lastSeen] = timestamp
        it[lastAttack] = timestamp
      }
    }
  }

  override fun countAttackersSince(since: Long): Int {
    return transaction(database) {
      val total = PlayerLogins.uuid.count()
      PlayerLogins.select(total)
        .where { PlayerLogins.lastAttack greaterEq since }
        .firstOrNull()
        ?.get(total)
        ?.toInt() ?: 0
    }
  }

  override fun countUniquePlayersSince(since: Long): Int {
    return transaction(database) {
      val total = PlayerLogins.uuid.count()
      PlayerLogins.select(total)
        .where { PlayerLogins.lastSeen greaterEq since }
        .firstOrNull()
        ?.get(total)
        ?.toInt() ?: 0
    }
  }

  override fun saveAiBuffer(playerUUID: UUID, buffer: Double, updatedAt: Long) {
    transaction(database) {
      val uuidString = playerUUID.toString()
      val updated =
        PlayerLogins.update({ PlayerLogins.uuid eq uuidString }) {
          it[PlayerLogins.aiBuffer] = buffer
          it[PlayerLogins.aiBufferUpdatedAt] = updatedAt
        }
      if (updated > 0) return@transaction
      try {
        PlayerLogins.insert {
          it[uuid] = uuidString
          it[lastSeen] = updatedAt
          it[aiBuffer] = buffer
          it[aiBufferUpdatedAt] = updatedAt
        }
      } catch (_: java.sql.SQLException) {
        PlayerLogins.update({ PlayerLogins.uuid eq uuidString }) {
          it[PlayerLogins.aiBuffer] = buffer
          it[PlayerLogins.aiBufferUpdatedAt] = updatedAt
        }
      }
    }
  }

  override fun loadAiBuffer(playerUUID: UUID): AiBufferState? {
    return transaction(database) {
      PlayerLogins.select(PlayerLogins.aiBuffer, PlayerLogins.aiBufferUpdatedAt)
        .where { PlayerLogins.uuid eq playerUUID.toString() }
        .firstOrNull()
        ?.let { row ->
          val updatedAt = row[PlayerLogins.aiBufferUpdatedAt]
          if (updatedAt == 0L) null
          else AiBufferState(buffer = row[PlayerLogins.aiBuffer], updatedAt = updatedAt)
        }
    }
  }

  private fun writeScore(statement: UpdateBuilder<*>, state: StoredScore) {
    statement[PlayerLogins.mitigationScore] = state.score
    statement[PlayerLogins.mitigationScoreAt] = state.updatedAt
    statement[PlayerLogins.mitigationSessions] = state.sessions
    statement[PlayerLogins.mitigationDays] = state.days
    statement[PlayerLogins.mitigationLastDay] = state.lastDay
  }

  override fun saveMitigationScore(playerUUID: UUID, state: StoredScore) {
    transaction(database) {
      val uuidString = playerUUID.toString()
      val updated =
        PlayerLogins.update({ PlayerLogins.uuid eq uuidString }) { writeScore(it, state) }
      if (updated > 0) return@transaction
      try {
        PlayerLogins.insert {
          it[uuid] = uuidString
          it[lastSeen] = state.updatedAt
          writeScore(it, state)
        }
      } catch (_: java.sql.SQLException) {
        PlayerLogins.update({ PlayerLogins.uuid eq uuidString }) { writeScore(it, state) }
      }
    }
  }

  override fun loadMitigationScore(playerUUID: UUID): StoredScore? {
    return transaction(database) {
      PlayerLogins.select(
          PlayerLogins.mitigationScore,
          PlayerLogins.mitigationScoreAt,
          PlayerLogins.mitigationSessions,
          PlayerLogins.mitigationDays,
          PlayerLogins.mitigationLastDay,
        )
        .where { PlayerLogins.uuid eq playerUUID.toString() }
        .firstOrNull()
        ?.let { row ->
          val updatedAt = row[PlayerLogins.mitigationScoreAt]
          if (updatedAt == 0L) null
          else
            StoredScore(
              score = row[PlayerLogins.mitigationScore],
              updatedAt = updatedAt,
              sessions = row[PlayerLogins.mitigationSessions],
              days = row[PlayerLogins.mitigationDays],
              lastDay = row[PlayerLogins.mitigationLastDay],
            )
        }
    }
  }

  override fun resetViolationLevel(playerUUID: UUID, punishGroupName: String) {
    transaction(database) {
      Punishments.deleteWhere {
        (Punishments.uuid eq playerUUID.toString()) and (Punishments.punishGroup eq punishGroupName)
      }
    }
  }

  override fun resetAllViolationLevels(playerUUID: UUID) {
    transaction(database) { Punishments.deleteWhere { Punishments.uuid eq playerUUID.toString() } }
  }

  override fun loadMonitorSettings(playerUUID: UUID): MonitorSettings? {
    return transaction(database) {
      MonitorSettingsTable.selectAll()
        .where { MonitorSettingsTable.uuid eq playerUUID.toString() }
        .firstOrNull()
        ?.let { row ->
          val mode = MonitorMode.fromConfig(row[MonitorSettingsTable.mode])
          val theme = MonitorTheme.fromConfig(row[MonitorSettingsTable.theme])
          val showName = MonitorNameMode.fromConfig(row[MonitorSettingsTable.showName])
          MonitorSettings(
            mode,
            theme,
            row[MonitorSettingsTable.showPing],
            row[MonitorSettingsTable.showDmg],
            row[MonitorSettingsTable.showTrend],
            showName,
            MonitorOutputKind.parseSet(row[MonitorSettingsTable.outputKind]),
            MonitorChatStyle.fromConfig(row[MonitorSettingsTable.chatStyle]),
          )
        }
    }
  }

  override fun saveMonitorSettings(playerUUID: UUID, settings: MonitorSettings) {
    transaction(database) {
      MonitorSettingsTable.upsert(
        onUpdate = {
          it[MonitorSettingsTable.mode] = insertValue(MonitorSettingsTable.mode)
          it[MonitorSettingsTable.theme] = insertValue(MonitorSettingsTable.theme)
          it[MonitorSettingsTable.showPing] = insertValue(MonitorSettingsTable.showPing)
          it[MonitorSettingsTable.showDmg] = insertValue(MonitorSettingsTable.showDmg)
          it[MonitorSettingsTable.showTrend] = insertValue(MonitorSettingsTable.showTrend)
          it[MonitorSettingsTable.showName] = insertValue(MonitorSettingsTable.showName)
          it[MonitorSettingsTable.outputKind] = insertValue(MonitorSettingsTable.outputKind)
          it[MonitorSettingsTable.chatStyle] = insertValue(MonitorSettingsTable.chatStyle)
        }
      ) {
        it[uuid] = playerUUID.toString()
        it[mode] = settings.mode.name
        it[theme] = settings.theme.name
        it[showPing] = settings.showPing
        it[showDmg] = settings.showDmg
        it[showTrend] = settings.showTrend
        it[showName] = settings.showName.name
        it[outputKind] = MonitorOutputKind.store(settings.outputs)
        it[chatStyle] = settings.chatStyle.name
      }
    }
  }

  private fun toViolation(row: ResultRow): Violation {
    return Violation(
      serverName = row[Violations.server],
      playerUUID = UUID.fromString(row[Violations.uuid]),
      playerName = row[Violations.playerName],
      checkName = row[Violations.checkName],
      verbose = row[Violations.verbose],
      vl = row[Violations.vl],
      createdAt = row[Violations.createdAtInstant],
      aiBuffer = row[Violations.aiBuffer],
      mitigationScore = row[Violations.mitigationScore],
      windows = row[Violations.aiWindows],
      highWindows = row[Violations.aiHighWindows],
      trail = row[Violations.probabilityTrail] ?: ByteArray(0),
    )
  }

  private object Violations : Table("violations") {
    val id: Column<Long> = long("id").autoIncrement()
    val server: Column<String> = varchar("server", 255)
    val uuid: Column<String> = varchar("uuid", 36)
    val playerName: Column<String> = varchar("player_name", 64)
    val checkName: Column<String> = varchar("check_name", 255)
    val verbose: Column<String> = text("verbose")
    val vl: Column<Int> = integer("vl")
    val createdAt: Column<Long> = long("created_at")
    val createdAtInstant: Column<Instant> =
      registerColumn("created_at_instant", ShardInstantColumnType()).default(Instant.EPOCH)
    val aiBuffer: Column<Double?> = double("ai_buffer").nullable()
    val mitigationScore: Column<Double?> = double("mitigation_score").nullable()
    val aiWindows: Column<Long?> = long("ai_windows").nullable()
    val aiHighWindows: Column<Long?> = long("ai_high_windows").nullable()
    val probabilityTrail: Column<ByteArray?> = binary("probability_trail", TRAIL_BYTES).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
      index(isUnique = false, uuid, createdAt)
      index(isUnique = false, createdAt)
      index(isUnique = false, uuid, createdAtInstant)
      index(isUnique = false, createdAtInstant)
    }
  }

  private object Punishments : Table("shard_punishments") {
    val uuid: Column<String> = varchar("uuid", 36)
    val punishGroup: Column<String> = varchar("punish_group", 255)
    val vl: Column<Int> = integer("vl")

    override val primaryKey = PrimaryKey(uuid, punishGroup)
  }

  private object MitigationEvents : Table("mitigation_events") {
    val id: Column<Long> = long("id").autoIncrement()
    val server: Column<String> = varchar("server", TEXT_LENGTH).default("server")
    val uuid: Column<String> = varchar("uuid", UUID_LENGTH)
    val playerName: Column<String> = varchar("player_name", NAME_LENGTH).default("")
    val rule: Column<String> = varchar("rule", TEXT_LENGTH)
    val tier: Column<String> = varchar("tier", TIER_LENGTH)
    val score: Column<Double> = double("score")
    val startedAt: Column<Long> = long("started_at")
    val endedAt: Column<Long> = long("ended_at")

    override val primaryKey = PrimaryKey(id)

    init {
      index(isUnique = false, uuid, endedAt)
      index(isUnique = false, endedAt)
    }
  }

  private object PlayerLogins : Table("player_logins") {
    val uuid: Column<String> = varchar("uuid", 36)
    val lastSeen: Column<Long> = long("last_seen")
    val lastAttack: Column<Long> = long("last_attack").default(0L)
    val aiBuffer: Column<Double> = double("ai_buffer").default(0.0)
    val aiBufferUpdatedAt: Column<Long> = long("ai_buffer_updated_at").default(0L)
    val mitigationScore: Column<Double> = double("mitigation_score").default(0.0)
    val mitigationScoreAt: Column<Long> = long("mitigation_score_at").default(0L)
    val mitigationSessions: Column<Int> = integer("mitigation_sessions").default(0)
    val mitigationDays: Column<Int> = integer("mitigation_days").default(0)
    val mitigationLastDay: Column<Long> = long("mitigation_last_day").default(0L)
    val aiHighWindows: Column<Long> = long("ai_high_windows").default(0L)
    val aiWindows: Column<Long> = long("ai_windows").default(0L)
    val probLow: Column<Long> = long("prob_low").default(0L)
    val probMid: Column<Long> = long("prob_mid").default(0L)
    val probHigh: Column<Long> = long("prob_high").default(0L)
    val aiStateAt: Column<Long> = long("ai_state_at").default(0L)
    val probabilityTrail: Column<ByteArray?> = binary("probability_trail", TRAIL_BYTES).nullable()

    override val primaryKey = PrimaryKey(uuid)

    init {
      index(isUnique = false, lastSeen)
    }
  }

  private object MonitorSettingsTable : Table("monitor_settings") {
    val uuid: Column<String> = varchar("uuid", 36)
    val mode: Column<String> = varchar("mode", 16)
    val theme: Column<String> = varchar("theme", 16)
    val showPing: Column<Boolean> = bool("show_ping")
    val showDmg: Column<Boolean> = bool("show_dmg")
    val showTrend: Column<Boolean> = bool("show_trend")
    val showName: Column<String> = varchar("show_name", 16)
    val outputKind: Column<String> = varchar("output_kind", 64)
    val chatStyle: Column<String> = varchar("chat_style", 16)

    override val primaryKey = PrimaryKey(uuid)
  }

  private companion object {
    private val VIOLATION_READ_COLUMNS =
      listOf<Expression<*>>(
        Violations.server,
        Violations.uuid,
        Violations.playerName,
        Violations.checkName,
        Violations.verbose,
        Violations.vl,
        Violations.createdAt,
        Violations.createdAtInstant,
        Violations.aiBuffer,
        Violations.mitigationScore,
        Violations.aiWindows,
        Violations.aiHighWindows,
        Violations.probabilityTrail,
      )

    private class ShardInstantColumnType : InstantColumnType<Instant>() {
      @OptIn(ExperimentalTime::class)
      override fun toInstant(value: Instant) = value.toKotlinInstant()

      @OptIn(ExperimentalTime::class)
      override fun fromInstant(instant: kotlin.time.Instant) = instant.toJavaInstant()

      override fun readObject(rs: RowApi, index: Int): Any? {
        return if (TransactionManager.current().db.dialect is SQLiteDialect) {
          rs.getString(index)?.let(::parseSqliteTimestamp)
        } else {
          super.readObject(rs, index)
        }
      }

      private fun parseSqliteTimestamp(rawValue: String): Any {
        return try {
          LocalDateTime.parse(rawValue, SQLITE_TIMESTAMP_FORMATTER)
        } catch (_: DateTimeParseException) {
          rawValue
        }
      }
    }

    private val SQLITE_TIMESTAMP_FORMATTER =
      DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
        .optionalEnd()
        .toFormatter()
  }
}
