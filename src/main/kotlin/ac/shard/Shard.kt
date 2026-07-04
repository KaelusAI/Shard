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
package ac.shard

import ac.shard.di.shardModules
import ac.shard.integration.ShardFlags
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import org.bstats.bukkit.Metrics
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class Shard : JavaPlugin() {
  private var core: ShardCore? = null
  private val packetEventsLoader = PacketEventsLoader(this)
  private var packetEventsLoadFailure: Throwable? = null
  private var runtimeStopped = false

  override fun onLoad() {
    migrateLegacyDataFolder()
    packetEventsLoadFailure = runCatching { packetEventsLoader.load() }.exceptionOrNull()
    if (server.pluginManager.getPlugin("WorldGuard") != null) {
      runCatching { ShardFlags.register(logger) }
        .onFailure { logger.log(Level.WARNING, "Failed to register WorldGuard flags", it) }
    }
  }

  override fun onEnable() {
    runtimeStopped = false
    packetEventsLoadFailure?.let { failure ->
      handleEnableFailure(failure)
      return
    }

    runCatching(::enableRuntime).onFailure(::handleEnableFailure)
  }

  override fun onDisable() {
    shutdownRuntime()
  }

  fun onReload() {
    core?.reload()
  }

  private companion object {
    const val BSTATS_PLUGIN_ID = 32301
    const val LEGACY_DATA_FOLDER = "SlothAC"
    val SKIP_MIGRATION = setOf("config.yml", "credentials.yml")
  }

  // One-time rebrand step: seed plugins/Shard from the old plugins/SlothAC folder. The legacy
  // folder is copied, never deleted, so a rollback to the old plugin keeps its data intact.
  private fun migrateLegacyDataFolder() {
    val target = dataFolder
    val legacy = target.parentFile?.resolve(LEGACY_DATA_FOLDER) ?: return
    if (!legacy.isDirectory || legacy == target || target.list()?.isNotEmpty() == true) {
      return
    }
    runCatching { copyDirectory(legacy.toPath(), target.toPath()) }
      .onSuccess {
        logger.info("Migrated legacy $LEGACY_DATA_FOLDER data folder into ${target.name}")
      }
      .onFailure {
        logger.log(Level.WARNING, "Failed to migrate legacy $LEGACY_DATA_FOLDER data", it)
      }
  }

  private fun copyDirectory(source: Path, target: Path) {
    Files.walk(source).use { paths ->
      paths.forEach { path ->
        val relative = source.relativize(path)
        if (relative.nameCount == 1 && relative.toString() in SKIP_MIGRATION) {
          return@forEach
        }
        val destination = target.resolve(relative)
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination)
        } else {
          destination.parent?.let(Files::createDirectories)
          Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES)
        }
      }
    }
  }

  private fun enableRuntime() {
    val koinApp = startKoin { modules(shardModules(this@Shard)) }
    core = koinApp.koin.get()
    core?.enable()
    Metrics(this, BSTATS_PLUGIN_ID)
  }

  private fun handleEnableFailure(failure: Throwable) {
    logger.log(Level.SEVERE, "Shard failed to start and will disable itself safely.", failure)
    shutdownRuntime()
    server.pluginManager.disablePlugin(this)
  }

  private fun shutdownRuntime() {
    if (runtimeStopped) {
      return
    }
    runtimeStopped = true

    runCatching { core?.disable() }
    core = null
    runCatching { stopKoin() }
    packetEventsLoader.shutdown()
  }
}
