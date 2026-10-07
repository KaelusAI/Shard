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
package ac.shard.database

import ac.shard.Shard
import ac.shard.config.ConfigManager
import ac.shard.platform.Lifecycle
import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import org.flywaydb.core.api.FlywayException

private const val PROBE_SECONDS = 30L
private const val PROBE_TIMEOUT_SECONDS = 2
private const val SHUTDOWN_WAIT_MILLIS = 5_000L

class DatabaseManager(private val plugin: Shard, private val configManager: ConfigManager) :
  Lifecycle {
  lateinit var database: ViolationDatabase
    private set

  private var dataSource: HikariDataSource? = null
  private val health = DatabaseHealth()
  private var resilient: ResilientViolationDatabase? = null
  private var probe: ScheduledExecutorService? = null

  override fun start() {
    val rawType = configManager.config.getString("database.type", "sqlite")
    val databaseType = DatabaseType.fromConfig(rawType)
    val environment =
      DatabaseEnvironment(
        dataDirectory = plugin.dataFolder.toPath(),
        logger = plugin.logger,
        classLoader = plugin.javaClass.classLoader,
      )
    val dataSourceFactory = DatabaseDataSourceFactory(environment, configManager)
    val migrationExecutor = DatabaseMigrationExecutor(environment)
    val sqliteRecovery = SqliteMigrationRecovery(environment, configManager, migrationExecutor)
    val fallbackDatabase = InMemoryViolationDatabase()
    if (SUPPORTED_DATABASE_TYPES.none { it.equals(rawType, ignoreCase = true) }) {
      environment.logger.warning(
        "Unknown database type $rawType, defaulting to sqlite. Supported types: sqlite, mysql, mariadb."
      )
    }

    val dataSourceResult = runCatching {
      createMigratedDataSource(
        databaseType = databaseType,
        dataSourceFactory = dataSourceFactory,
        migrationExecutor = migrationExecutor,
        sqliteRecovery = sqliteRecovery,
      )
    }

    dataSourceResult
      .onSuccess { connect(it, fallbackDatabase) }
      .onFailure { degrade(it, fallbackDatabase) }
  }

  private fun connect(source: HikariDataSource, fallback: ViolationDatabase) {
    dataSource = source
    val persistentDatabase =
      SqlViolationDatabase(org.jetbrains.exposed.v1.jdbc.Database.connect(source))
    val resilient =
      ResilientViolationDatabase(
        primary = persistentDatabase,
        fallback = fallback,
        health = health,
        logger = plugin.logger,
      )
    database = resilient
    this.resilient = resilient
    health.markPersistent()
    probe = startProbe(source, resilient)
  }

  private fun degrade(failure: Throwable, fallback: ViolationDatabase) {
    plugin.logger.log(
      Level.WARNING,
      "Persistent database storage is unavailable. Shard will continue in degraded mode " +
        "using in-memory storage. Data will work for the current runtime, but it will not " +
        "persist across restart.",
      failure,
    )
    dataSource = null
    database = fallback
    health.markDegraded(failure)
  }

  val isAvailable: Boolean
    get() = health.isPersistentAvailable()

  val failureCause: Throwable?
    get() = health.failureCause

  private fun createMigratedDataSource(
    databaseType: DatabaseType,
    dataSourceFactory: DatabaseDataSourceFactory,
    migrationExecutor: DatabaseMigrationExecutor,
    sqliteRecovery: SqliteMigrationRecovery,
  ): HikariDataSource {
    val initialDataSource = dataSourceFactory.create(databaseType)
    return runCatching {
        if (databaseType == DatabaseType.SQLITE) {
          sqliteRecovery.migrate(initialDataSource)
        } else {
          migrationExecutor.migrate(initialDataSource, databaseType)
          initialDataSource
        }
      }
      .getOrElse { exception ->
        closeQuietly(initialDataSource)
        if (exception is FlywayException) {
          throw IllegalStateException("Database migrations failed", exception)
        }
        throw exception
      }
  }

  val pendingWrites: Int
    get() = resilient?.pendingWrites ?: 0

  override fun stop() {
    probe?.shutdownNow()
    resilient?.let {
      it.awaitIdle(SHUTDOWN_WAIT_MILLIS)
      if (!health.isPersistentAvailable()) runCatching { it.recover() }
    }
    val dataSource = dataSource ?: return
    if (!dataSource.isClosed) {
      dataSource.close()
    }
  }

  private fun startProbe(
    source: HikariDataSource,
    database: ResilientViolationDatabase,
  ): ScheduledExecutorService {
    val executor = Executors.newSingleThreadScheduledExecutor { r ->
      Thread(r, "Shard-DatabaseProbe").apply { isDaemon = true }
    }
    executor.scheduleWithFixedDelay(
      {
        if (!health.isPersistentAvailable() && reachable(source)) {
          runCatching { database.recover() }
        }
      },
      PROBE_SECONDS,
      PROBE_SECONDS,
      TimeUnit.SECONDS,
    )
    return executor
  }

  private fun reachable(source: HikariDataSource): Boolean =
    runCatching { source.connection.use { it.isValid(PROBE_TIMEOUT_SECONDS) } }.getOrDefault(false)

  private fun closeQuietly(dataSource: HikariDataSource) {
    if (!dataSource.isClosed) {
      runCatching { dataSource.close() }
    }
  }
}
