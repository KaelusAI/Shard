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
package ac.shard.di

import ac.shard.Shard
import ac.shard.ShardCore
import ac.shard.ai.AiResponseParser
import ac.shard.ai.AiSerializer
import ac.shard.ai.AiService
import ac.shard.ai.DefaultAiService
import ac.shard.ai.JacksonAiResponseParser
import ac.shard.alert.AlertManager
import ac.shard.api.ShardApi
import ac.shard.api.event.ShardEventBus
import ac.shard.api.event.internal.ShardEventBusImpl
import ac.shard.api.internal.AiApiImpl
import ac.shard.api.internal.CheckApiImpl
import ac.shard.api.internal.MonitorApiImpl
import ac.shard.api.internal.PunishmentApiImpl
import ac.shard.api.internal.ShardApiImpl
import ac.shard.api.service.AiApi
import ac.shard.api.service.CheckApi
import ac.shard.api.service.MonitorApi
import ac.shard.api.service.PunishmentApi
import ac.shard.checks.CheckFactory
import ac.shard.checks.CheckManager
import ac.shard.checks.impl.ai.ActionManager
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.checks.impl.ai.DataCollectorCheck
import ac.shard.checks.impl.ai.DataCollectorManager
import ac.shard.checks.impl.ai.PersistentBufferService
import ac.shard.checks.impl.misc.ClientBrand
import ac.shard.command.CommandManager
import ac.shard.command.CommandRegister
import ac.shard.command.ShardCommand
import ac.shard.command.commands.admin.AlertsCommand
import ac.shard.command.commands.admin.BrandsCommand
import ac.shard.command.commands.admin.ConnectCommand
import ac.shard.command.commands.admin.DataCollectCommand
import ac.shard.command.commands.admin.ExemptCommand
import ac.shard.command.commands.admin.PunishCommand
import ac.shard.command.commands.admin.ReloadCommand
import ac.shard.command.commands.admin.SuspiciousCommand
import ac.shard.command.commands.info.HelpCommand
import ac.shard.command.commands.info.HistoryCommand
import ac.shard.command.commands.info.LogsCommand
import ac.shard.command.commands.info.MonitorCommand
import ac.shard.command.commands.info.MonitorInfoCommand
import ac.shard.command.commands.info.MonitorOutputSelector
import ac.shard.command.commands.info.MonitorSettingsCommand
import ac.shard.command.commands.info.ProfileCommand
import ac.shard.command.commands.info.StatsCommand
import ac.shard.command.commands.info.ViewCommand
import ac.shard.command.handler.ShardCommandFailureHandler
import ac.shard.config.ConfigManager
import ac.shard.config.LocaleManager
import ac.shard.connect.ConnectService
import ac.shard.connect.CredentialsStore
import ac.shard.coroutines.ShardCoroutines
import ac.shard.damage.AiDamageProcessor
import ac.shard.damage.DamageProcessor
import ac.shard.database.DatabaseManager
import ac.shard.debug.DebugManager
import ac.shard.event.DamageEvent
import ac.shard.integration.WorldGuardManager
import ac.shard.monitor.core.ComponentCache
import ac.shard.monitor.core.MonitorSampler
import ac.shard.monitor.core.MonitorSettingsService
import ac.shard.monitor.core.ScoreboardPacketBridge
import ac.shard.monitor.core.ScoreboardSlotObserver
import ac.shard.monitor.core.ScoreboardSlotRegistry
import ac.shard.monitor.hud.MonitorFrameBuilder
import ac.shard.monitor.hud.MonitorHudService
import ac.shard.monitor.hud.MonitorLiveChatListener
import ac.shard.monitor.hud.MonitorOutput
import ac.shard.monitor.hud.MonitorOutputFailureSink
import ac.shard.monitor.hud.MonitorOutputGuard
import ac.shard.monitor.hud.MonitorOutputRegistry
import ac.shard.monitor.hud.MonitorRuntime
import ac.shard.monitor.hud.MonitorTargetIndex
import ac.shard.monitor.hud.MonitorTargetsService
import ac.shard.monitor.hud.output.ActionBarOutput
import ac.shard.monitor.hud.output.BossBarOutput
import ac.shard.monitor.hud.output.ChatOutput
import ac.shard.monitor.hud.output.ChatSink
import ac.shard.monitor.hud.output.SidebarOutput
import ac.shard.monitor.hud.output.TabListOutput
import ac.shard.monitor.view.MonitorViewService
import ac.shard.packet.PacketListener
import ac.shard.platform.scheduler.PlatformScheduler
import ac.shard.platform.scheduler.PlatformSchedulerFactory
import ac.shard.player.ExemptManager
import ac.shard.player.PlayerDataManager
import ac.shard.punishment.PunishmentManager
import ac.shard.redis.CrossServerAlertService
import ac.shard.redis.CrossServerSuspiciousService
import ac.shard.redis.RedisManager
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.sender.SenderFactory
import ac.shard.server.AIServerProvider
import ac.shard.telemetry.TelemetryService
import ac.shard.utils.MessageUtil
import java.util.logging.Logger
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import org.bukkit.command.CommandSender
import org.incendo.cloud.SenderMapper
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

fun shardModules(plugin: Shard) =
  listOf(
    coreModule(plugin),
    monitorModule(),
    aiModule(),
    apiModule(),
    commandModule(),
    checkModule(),
  )

private fun coreModule(plugin: Shard) = module {
  single { plugin }
  single { BukkitAudiences.create(plugin) }
  single<Logger> { plugin.logger }
  single<PlatformScheduler> { PlatformSchedulerFactory.create() }
  single<ShardEventBus> { ShardEventBusImpl() }

  singleOf(::SchedulerService)
  singleOf(::ShardCoroutines)
  singleOf(::CredentialsStore)
  singleOf(::ConfigManager)
  singleOf(::ConnectService)
  singleOf(::TelemetryService)
  singleOf(::LocaleManager)
  singleOf(::DatabaseManager)
  singleOf(::DebugManager)
  singleOf(::AIServerProvider)
  singleOf(::AlertManager)
  singleOf(::RedisManager)
  singleOf(::CrossServerAlertService)
  singleOf(::CrossServerSuspiciousService)
  single { ComponentCache() }
  singleOf(::MonitorSampler)
  single { ScoreboardPacketBridge(get()) }
  singleOf(::ScoreboardSlotRegistry)
  singleOf(::ScoreboardSlotObserver)
  singleOf(::MonitorSettingsService)
  singleOf(::MonitorViewService)
  singleOf(::ExemptManager)
  singleOf(::DataCollectorManager)
  singleOf(::PersistentBufferService)
  singleOf(::WorldGuardManager)
  single<RegionProvider> { get<WorldGuardManager>() }
  single<DamageProcessor> { AiDamageProcessor(get()) }

  singleOf(::SenderFactory).bind<SenderMapper<CommandSender, Sender>>()

  singleOf(::ShardCommandFailureHandler)

  singleOf(::PlayerDataManager)
  singleOf(::PacketListener)
  singleOf(::DamageEvent)

  singleOf(::ShardCore)
}

private fun monitorModule() = module {
  singleOf(::MonitorFrameBuilder)
  singleOf(::MonitorTargetIndex)
  singleOf(::MonitorTargetsService)
  singleOf(::MonitorOutputSelector)
  single<MonitorOutputFailureSink> {
    val scope = this
    MonitorOutputFailureSink { viewerId, kind, phase, error ->
      scope.get<MonitorHudService>().onOutputFailed(viewerId, kind, phase, error)
    }
  }
  single<ChatSink> {
    ChatSink { viewer, raw -> MessageUtil.sendMessage(viewer, MessageUtil.format(raw)) }
  }
  single<MonitorOutput>(named("actionbar")) {
    MonitorOutputGuard(ActionBarOutput(get(), get()), get(), get())
  }
  single<MonitorOutput>(named("bossbar")) {
    MonitorOutputGuard(BossBarOutput(get(), get()), get(), get())
  }
  single<MonitorOutput>(named("sidebar")) {
    MonitorOutputGuard(SidebarOutput(get(), get()), get(), get())
  }
  single { ChatOutput(get()) }
  single<MonitorOutput>(named("chat")) { MonitorOutputGuard(get<ChatOutput>(), get(), get()) }
  single<MonitorOutput>(named("tablist")) {
    MonitorOutputGuard(TabListOutput(get(), get()), get(), get())
  }
  single { MonitorOutputRegistry(getAll()) }
  singleOf(::MonitorHudService)
  singleOf(::MonitorLiveChatListener)
  singleOf(::MonitorRuntime)
}

private fun aiModule() = module {
  singleOf(::AiSerializer)
  singleOf(::JacksonAiResponseParser).bind<AiResponseParser>()
  singleOf(::DefaultAiService).bind<AiService>()
}

private fun apiModule() = module {
  singleOf(::AiApiImpl).bind<AiApi>()
  singleOf(::CheckApiImpl).bind<CheckApi>()
  singleOf(::MonitorApiImpl).bind<MonitorApi>()
  singleOf(::PunishmentApiImpl).bind<PunishmentApi>()
  singleOf(::ShardApiImpl).bind<ShardApi>()
}

private fun commandModule() = module {
  includes(adminCommandsModule(), infoCommandsModule())

  single { CommandRegister(getAll(), get()) }
  singleOf(::CommandManager)
}

private fun adminCommandsModule() = module {
  singleOf(::AlertsCommand).bind<ShardCommand>()
  singleOf(::BrandsCommand).bind<ShardCommand>()
  singleOf(::ConnectCommand).bind<ShardCommand>()
  singleOf(::DataCollectCommand).bind<ShardCommand>()
  singleOf(::ExemptCommand).bind<ShardCommand>()
  singleOf(::PunishCommand).bind<ShardCommand>()
  singleOf(::ReloadCommand).bind<ShardCommand>()
  singleOf(::SuspiciousCommand).bind<ShardCommand>()
}

private fun infoCommandsModule() = module {
  singleOf(::HelpCommand).bind<ShardCommand>()
  singleOf(::HistoryCommand).bind<ShardCommand>()
  singleOf(::LogsCommand).bind<ShardCommand>()
  singleOf(::MonitorCommand).bind<ShardCommand>()
  singleOf(::MonitorSettingsCommand).bind<ShardCommand>()
  singleOf(::MonitorInfoCommand).bind<ShardCommand>()
  singleOf(::ProfileCommand).bind<ShardCommand>()
  singleOf(::StatsCommand).bind<ShardCommand>()
  singleOf(::ViewCommand).bind<ShardCommand>()
}

private fun checkModule() = module {
  single<CheckFactory>(named("action")) { CheckFactory { player -> ActionManager(player) } }
  single<CheckFactory>(named("ai")) {
    CheckFactory { player ->
      AiCheck(player, get(), get(), get(), get(), get(), get(), get(), get())
    }
  }
  single<CheckFactory>(named("collector")) {
    CheckFactory { player -> DataCollectorCheck(player, get(), get(), get()) }
  }
  single<CheckFactory>(named("brand")) {
    CheckFactory { player -> ClientBrand(player, get(), get()) }
  }

  single<Set<CheckFactory>> { getAll<CheckFactory>().toSet() }

  single<CheckManager.Factory> { CheckManager.Factory { player -> CheckManager(player, get()) } }
  single<PunishmentManager.Factory> {
    PunishmentManager.Factory { player ->
      PunishmentManager(player, get(), get(), get(), get(), get(), get(), get())
    }
  }
}
