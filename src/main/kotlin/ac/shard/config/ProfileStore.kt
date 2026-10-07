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
package ac.shard.config

import ac.shard.ai.stream.ProfileParser
import ac.shard.ai.stream.StreamProfile
import ac.shard.http.Json
import ac.shard.utils.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.logging.Logger
import tools.jackson.databind.JsonNode

@Suppress("TooManyFunctions")
class ProfileStore(
  private val logger: Logger,
  private val file: Path,
  private val local: () -> LocalAiSettings,
  private val writer: Executor,
) {
  @Volatile
  var current: StreamProfile? = null
    private set

  @Volatile private var received: StreamProfile? = null
  @Volatile private var recent: List<StreamProfile> = emptyList()
  private val rejected = HashSet<Long>()
  private val listeners = CopyOnWriteArrayList<(StreamProfile?, StreamProfile) -> Unit>()

  fun addListener(listener: (StreamProfile?, StreamProfile) -> Unit) {
    listeners += listener
  }

  @Synchronized fun isRejected(node: JsonNode): Boolean = crcOf(node) in rejected

  fun recent(crc: Long): StreamProfile? =
    current?.takeIf { it.crc == crc } ?: recent.firstOrNull { it.crc == crc }?.let(::narrow)

  @Synchronized
  fun accept(node: JsonNode, sentUnderCrc: Long?): Boolean {
    val previous = current
    val answersCurrent = previous == null || sentUnderCrc == null || sentUnderCrc == previous.crc
    if (!answersCurrent || !install(node, previous)) return false
    persist(node)
    return true
  }

  @Synchronized
  fun loadStored() {
    if (current != null || !Files.isRegularFile(file)) return
    runCatching { Json.mapper.readTree(file.toFile()) }
      .onSuccess { install(it, null) }
      .onFailure { logger.warning("[AI] Stored model profile is unreadable: ${it.message}") }
  }

  @Suppress("ReturnCount")
  private fun install(node: JsonNode, previous: StreamProfile?): Boolean {
    val crc = crcOf(node)
    if (crc == null || crc == previous?.crc || crc in rejected) return false
    val profile =
      ProfileParser.parse(node).getOrElse { error ->
        rejected += crc
        logger.severe("[AI] Model profile ${"%08x".format(crc)} rejected: ${error.message}")
        return false
      }
    recent = (listOf(profile) + recent).take(RECENT_PROFILES)
    received = profile
    val active = narrow(profile)
    current = active
    logger.info(
      "[AI] Model profile ${previous?.configHeader ?: "none"} -> ${profile.configHeader}: " +
        active.active.joinToString(", ") { it.displayTitle } +
        ", primary ${profile.primary.displayTitle}"
    )
    ProfileParser.warnings(profile).forEach(logger::warning)
    warnPrimaryOff(profile)
    listeners.forEach { it(previous, active) }
    return true
  }

  @Synchronized
  fun renarrow() {
    val source = received ?: return
    val previous = current
    val active = narrow(source)
    current = active
    warnPrimaryOff(source)
    if (active !== previous) listeners.forEach { it(previous, active) }
  }

  private fun persist(node: JsonNode) {
    val raw = Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
    writer.execute {
      runCatching { AtomicFiles.replace(file) { Files.writeString(it, raw) } }
        .onFailure { logger.warning("[AI] Could not store the model profile: ${it.message}") }
    }
  }

  private fun locallyOff(): Set<String> = local().models.filterValues { !it.enabled }.keys

  private fun narrow(profile: StreamProfile): StreamProfile = profile.narrowed(locallyOff())

  private fun warnPrimaryOff(profile: StreamProfile) {
    if (profile.primary.id in locallyOff()) {
      logger.warning(
        "[AI] ai.models turns off ${profile.primary.id}, the primary model; it stays on"
      )
    }
  }

  private fun crcOf(node: JsonNode): Long? =
    node.path("profile_crc").asString("").toLongOrNull(CRC_RADIX)

  private companion object {
    const val CRC_RADIX = 16
    const val RECENT_PROFILES = 4
  }
}
