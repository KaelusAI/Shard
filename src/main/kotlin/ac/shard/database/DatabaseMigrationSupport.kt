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

import com.zaxxer.hikari.HikariDataSource
import java.util.logging.Logger
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.ValidateResult

internal val SUPPORTED_DATABASE_TYPES = setOf("sqlite", "mysql", "mariadb")
internal const val MARIADB_DRIVER_CLASS = "org.mariadb.jdbc.Driver"
internal const val MARIADB_JDBC_SCHEME = "jdbc:mariadb://"
private const val COMMON_MIGRATION_LOCATION = "classpath:db/migration/common"
private const val DEFAULT_LOCATIONS_COUNT = 2
private const val COMPAT_LOCATIONS_COUNT = 3
private const val SQLITE_BASELINE_VERSION = "0"
private const val LEGACY_SQLITE_COMPAT_VERSION = "0.1"
private const val LEGACY_SQLITE_COMPAT_LOCATION = "classpath:db/migration/sqlitecompat"
private const val SQLITE_SCHEMA_VERSION = "1"
private const val SQLITE_TRANSITIONAL_V1_CHECKSUM = 1087069697
private const val SQLITE_V1_SCRIPT_PATH_SUFFIX = "db/migration/sqlite/V1__create_schema.sql"
private const val LEGACY_SQLITE_COMPAT_LOG =
  "Detected a legacy SQLite schema. Enabling the compatibility migration location."

internal fun buildMigrationFlyway(
  classLoader: ClassLoader,
  logger: Logger,
  dataSource: HikariDataSource,
  databaseType: DatabaseType,
  announceCompat: Boolean = true,
): Flyway {
  val defaultLocations = defaultFlywayLocations(databaseType)
  if (databaseType != DatabaseType.SQLITE) {
    return buildFlyway(classLoader, dataSource, defaultLocations)
  }

  val useCompatLocation =
    hasAppliedMigrationVersion(dataSource, LEGACY_SQLITE_COMPAT_VERSION) ||
      sqliteRequiresLegacyCompat(dataSource)

  return if (useCompatLocation) {
    if (announceCompat) {
      logger.info(LEGACY_SQLITE_COMPAT_LOG)
    }
    buildFlyway(classLoader, dataSource, defaultLocations + LEGACY_SQLITE_COMPAT_LOCATION)
  } else {
    buildFlyway(classLoader, dataSource, defaultLocations)
  }
}

internal fun requiresExplicitBaseline(
  dataSource: HikariDataSource,
  databaseType: DatabaseType,
): Boolean {
  if (databaseType != DatabaseType.SQLITE) {
    return false
  }

  dataSource.connection.use { connection ->
    return sqliteRequiresExplicitBaseline(connection)
  }
}

internal fun isRepairableSqliteV1ChecksumMismatch(
  validation: ValidateResult,
  dataSource: HikariDataSource,
): Boolean {
  val invalidMigration = validation.invalidMigrations.singleOrNull()
  val matchesKnownMismatch =
    invalidMigration != null &&
      invalidMigration.version == SQLITE_SCHEMA_VERSION &&
      invalidMigration.filepath?.endsWith(SQLITE_V1_SCRIPT_PATH_SUFFIX, ignoreCase = true) ==
        true &&
      invalidMigration.errorDetails.errorMessage.contains("checksum mismatch", ignoreCase = true)

  return matchesKnownMismatch &&
    dataSource.connection.use { connection ->
      readAppliedMigrationChecksum(connection, SQLITE_SCHEMA_VERSION) ==
        SQLITE_TRANSITIONAL_V1_CHECKSUM
    }
}

private fun defaultFlywayLocations(databaseType: DatabaseType): List<String> {
  return listOf(COMMON_MIGRATION_LOCATION, "classpath:db/migration/${databaseType.flywayLocation}")
}

private fun buildFlyway(
  classLoader: ClassLoader,
  dataSource: HikariDataSource,
  locations: List<String>,
): Flyway {
  require(locations.size == DEFAULT_LOCATIONS_COUNT || locations.size == COMPAT_LOCATIONS_COUNT) {
    "Unexpected Flyway locations count: ${locations.size}"
  }
  val configuration =
    Flyway.configure(classLoader)
      .dataSource(dataSource)
      .baselineVersion(SQLITE_BASELINE_VERSION)
      .validateOnMigrate(true)
      .outOfOrder(true)
  if (locations.size == DEFAULT_LOCATIONS_COUNT) {
    configuration.locations(locations[0], locations[1])
  } else {
    configuration.locations(locations[0], locations[1], locations[2])
  }
  return configuration.load()
}

private fun hasAppliedMigrationVersion(dataSource: HikariDataSource, version: String): Boolean {
  dataSource.connection.use { connection ->
    if (!flywaySchemaHistoryTableExists(connection)) {
      return false
    }
    connection
      .prepareStatement(
        """
        SELECT 1
        FROM flyway_schema_history
        WHERE version = ? AND success = 1
        LIMIT 1
        """
          .trimIndent()
      )
      .use { statement ->
        statement.setString(1, version)
        statement.executeQuery().use { resultSet ->
          return resultSet.next()
        }
      }
  }
}

internal fun flywaySchemaHistoryTableExists(connection: java.sql.Connection): Boolean {
  connection.metaData.getTables(null, null, "flyway_schema_history", arrayOf("TABLE")).use {
    resultSet ->
    return resultSet.next()
  }
}

private fun sqliteRequiresLegacyCompat(dataSource: HikariDataSource): Boolean {
  dataSource.connection.use { connection ->
    return sqliteLegacyCompatRequired(connection)
  }
}

private fun readAppliedMigrationChecksum(connection: java.sql.Connection, version: String): Int? {
  connection
    .prepareStatement(
      """
      SELECT checksum
      FROM flyway_schema_history
      WHERE version = ? AND success = 1
      ORDER BY installed_rank DESC
      LIMIT 1
      """
        .trimIndent()
    )
    .use { statement ->
      statement.setString(1, version)
      statement.executeQuery().use { resultSet ->
        return if (resultSet.next()) resultSet.getInt("checksum") else null
      }
    }
}
