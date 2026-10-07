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

import ac.shard.PacketEventsLoader
import ac.shard.Shard
import ac.shard.ShardCore
import ac.shard.ShardReloader
import ac.shard.ai.label.LabelCatalog
import ac.shard.alert.AlertManager
import ac.shard.alert.NetworkPublisher
import ac.shard.api.alert.AlertService
import ac.shard.api.detection.DetectionService
import ac.shard.api.exemption.ExemptionService
import ac.shard.api.history.HistoryService
import ac.shard.api.impl.AlertServiceImpl
import ac.shard.api.impl.ApiTasks
import ac.shard.api.impl.DetectionServiceImpl
import ac.shard.api.impl.ExemptionServiceImpl
import ac.shard.api.impl.HistoryServiceImpl
import ac.shard.api.impl.MitigationServiceImpl
import ac.shard.api.impl.NetworkServiceImpl
import ac.shard.api.impl.PlayerServiceImpl
import ac.shard.api.impl.PunishmentServiceImpl
import ac.shard.api.impl.Sessions
import ac.shard.api.impl.ShardImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.api.mitigation.MitigationService
import ac.shard.api.network.NetworkService
import ac.shard.api.player.PlayerService
import ac.shard.api.punishment.PunishmentService
import ac.shard.command.CommandManager
import ac.shard.command.CommandRegister
import ac.shard.command.ShardCommand
import ac.shard.command.TargetResolver
import ac.shard.command.commands.admin.AlertsCommand
import ac.shard.command.commands.admin.BrandsCommand
import ac.shard.command.commands.admin.BufferCommand
import ac.shard.command.commands.admin.CollectCommand
import ac.shard.command.commands.admin.ConnectCommand
import ac.shard.command.commands.admin.EditorCommand
import ac.shard.command.commands.admin.ExemptCommand
import ac.shard.command.commands.admin.MitigationsCommand
import ac.shard.command.commands.admin.PunishCommand
import ac.shard.command.commands.admin.ReloadCommand
import ac.shard.command.commands.admin.SetupCommand
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
import ac.shard.config.yaml.YamlFileStore
import ac.shard.connect.ConnectService
import ac.shard.connect.CredentialsStore
import ac.shard.connect.DeviceFlowPoller
import ac.shard.data.CollectManager
import ac.shard.database.DatabaseManager
import ac.shard.debug.DebugManager
import ac.shard.detection.AiSnapshotStore
import ac.shard.detection.InferenceServices
import ac.shard.detection.PersistentBufferService
import ac.shard.detection.Predictions
import ac.shard.detection.SuspicionPolicy
import ac.shard.editor.EditorApply
import ac.shard.editor.EditorSessionStore
import ac.shard.editor.EditorSnapshotBuilder
import ac.shard.editor.ResultGuard
import ac.shard.editor.SessionKind
import ac.shard.http.ShardHttp
import ac.shard.integration.WorldGuardManager
import ac.shard.mitigation.HitStamps
import ac.shard.mitigation.MitigationLogStore
import ac.shard.mitigation.MitigationRuntime
import ac.shard.mitigation.MitigationScoreStore
import ac.shard.mitigation.MitigationScorer
import ac.shard.mitigation.MitigationSettingsSource
import ac.shard.mitigation.MitigationSkip
import ac.shard.mitigation.RuleEngine
import ac.shard.monitor.MonitorServices
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
import ac.shard.monitor.hud.MonitorOutputFailures
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
import ac.shard.network.NetworkAlertService
import ac.shard.network.NetworkServices
import ac.shard.network.NetworkSuspiciousService
import ac.shard.network.RedisManager
import ac.shard.packet.ClientActions
import ac.shard.packet.ClientBrandHandler
import ac.shard.packet.FlyingProcessor
import ac.shard.packet.PacketListener
import ac.shard.packet.PacketSendListener
import ac.shard.panel.PanelClient
import ac.shard.panel.PanelSessionService
import ac.shard.panel.PendingLinkStore
import ac.shard.panel.ServerLink
import ac.shard.panel.SessionRunner
import ac.shard.platform.scheduler.PlatformScheduler
import ac.shard.platform.scheduler.PlatformSchedulerFactory
import ac.shard.player.ExemptManager
import ac.shard.player.PlayerDataManager
import ac.shard.player.PlayerDirectory
import ac.shard.player.PlayerPersistence
import ac.shard.player.PlayerThreadRefreshListener
import ac.shard.player.ShardPlayerFactory
import ac.shard.punishment.PunishmentManager
import ac.shard.punishment.cloud.PunishmentSync
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.sender.SenderFactory
import ac.shard.server.AIServerProvider
import ac.shard.telemetry.TelemetryService
import ac.shard.utils.Messages
import ac.shard.utils.WallClock
import java.util.logging.Logger
import kotlin.random.Random
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.incendo.cloud.SenderMapper
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

fun shardModules(plugin: Shard, packetEvents: PacketEventsLoader) =
  listOf(
    module { single { packetEvents } },
    coreModule(plugin),
    monitorModule(),
    apiModule(),
    commandModule(),
    checkModule(),
  )

@Suppress("LongMethod")
private fun coreModule(plugin: Shard) = module {
  single { plugin }
  single { BukkitAudiences.create(plugin) }
  single<Logger> { plugin.logger }
  single<Server> { plugin.server }
  single<PlatformScheduler> { PlatformSchedulerFactory.create() }
  singleOf(::ShardEvents)
  singleOf(::Predictions)
  singleOf(::ShardHttp)
  singleOf(::Messages)

  singleOf(::SchedulerService)
  singleOf(::CredentialsStore)
  singleOf(::ConfigManager)
  singleOf(::ConnectService)
  singleOf(::DeviceFlowPoller)
  singleOf(::PanelClient)
  singleOf(::PanelSessionService)
  single { PendingLinkStore(plugin.dataFolder) }
  singleOf(::ServerLink)
  single {
    val folder = plugin.dataFolder
    SessionRunner(
      plugin = plugin,
      reloader = get(),
      sessions = get(),
      snapshots = EditorSnapshotBuilder(folder),
      apply = EditorApply(folder, YamlFileStore(folder, plugin.logger)),
      guard = ResultGuard(),
      scheduler = get(),
      stores =
        mapOf(
          SessionKind.SETUP to EditorSessionStore(folder, SessionKind.SETUP),
          SessionKind.EDITOR to EditorSessionStore(folder, SessionKind.EDITOR),
        ),
    )
  }
  singleOf(::PunishmentSync)
  singleOf(::TelemetryService)
  singleOf(::LocaleManager)
  singleOf(::DatabaseManager)
  singleOf(::DebugManager)
  singleOf(::AIServerProvider)
  singleOf(::AlertManager)
  singleOf(::RedisManager)
  singleOf(::NetworkAlertService) bind NetworkPublisher::class
  singleOf(::NetworkSuspiciousService)
  singleOf(::SuspicionPolicy)
  single { ComponentCache() }
  singleOf(::MonitorSampler)
  singleOf(::ScoreboardPacketBridge)
  singleOf(::ScoreboardSlotRegistry)
  singleOf(::ScoreboardSlotObserver)
  singleOf(::MonitorSettingsService)
  singleOf(::MonitorViewService)
  singleOf(::ExemptManager)
  singleOf(::CollectManager)
  singleOf(::PersistentBufferService)
  singleOf(::WorldGuardManager)
  single<RegionProvider> { get<WorldGuardManager>() }

  single<WallClock> { WallClock.SYSTEM }
  single<Random> { Random.Default }
  single {
    val config = get<ConfigManager>()
    MitigationSettingsSource { config.mitigationSettings }
  }
  singleOf(::HitStamps)
  singleOf(::MitigationScorer)
  singleOf(::MitigationSkip)
  singleOf(::RuleEngine)
  singleOf(::MitigationScoreStore)
  singleOf(::MitigationLogStore)
  singleOf(::AiSnapshotStore)
  singleOf(::MitigationRuntime)

  singleOf(::SenderFactory).bind<SenderMapper<CommandSender, Sender>>()

  singleOf(::ShardCommandFailureHandler)

  singleOf(::PlayerPersistence)
  singleOf(::PlayerDataManager)
  singleOf(::FlyingProcessor)
  singleOf(::ClientActions)
  singleOf(::PacketListener)
  singleOf(::PacketSendListener)

  singleOf(::NetworkServices)
  singleOf(::ShardReloader)
  singleOf(::ShardCore)
}

private fun monitorModule() = module {
  singleOf(::MonitorServices)
  single<LabelCatalog> { get<ConfigManager>().labelCatalog }
  singleOf(::MonitorFrameBuilder)
  singleOf(::MonitorTargetIndex)
  singleOf(::MonitorTargetsService)
  singleOf(::MonitorOutputSelector)
  single { MonitorOutputFailures() } bind MonitorOutputFailureSink::class
  single<ChatSink> {
    val messages = get<Messages>()
    ChatSink { viewer, raw -> messages.sendMessage(viewer, messages.format(raw)) }
  }
  singleOf(::ActionBarOutput)
  singleOf(::BossBarOutput)
  singleOf(::SidebarOutput)
  singleOf(::ChatOutput)
  singleOf(::TabListOutput)
  single {
    val logger = get<Logger>()
    val failures = get<MonitorOutputFailureSink>()
    val outputs =
      listOf<MonitorOutput>(
        get<ActionBarOutput>(),
        get<BossBarOutput>(),
        get<SidebarOutput>(),
        get<ChatOutput>(),
        get<TabListOutput>(),
      )
    MonitorOutputRegistry(outputs.map { MonitorOutputGuard(it, logger, failures) })
  }
  singleOf(::MonitorHudService)
  singleOf(::MonitorLiveChatListener)
  singleOf(::MonitorRuntime)
}

private fun apiModule() = module {
  singleOf(::Sessions)
  singleOf(::ApiTasks)
  singleOf(::PlayerServiceImpl).bind<PlayerService>()
  singleOf(::DetectionServiceImpl).bind<DetectionService>()
  singleOf(::PunishmentServiceImpl).bind<PunishmentService>()
  singleOf(::MitigationServiceImpl).bind<MitigationService>()
  singleOf(::ExemptionServiceImpl).bind<ExemptionService>()
  singleOf(::AlertServiceImpl).bind<AlertService>()
  singleOf(::HistoryServiceImpl).bind<HistoryService>()
  singleOf(::NetworkServiceImpl).bind<NetworkService>()
  singleOf(::ShardImpl)
}

private fun commandModule() = module {
  includes(adminCommandsModule(), infoCommandsModule())

  single { CommandRegister(get(), getAll(), get(), get()) }
  singleOf(::CommandManager)
}

private fun adminCommandsModule() = module {
  singleOf(::AlertsCommand).bind<ShardCommand>()
  singleOf(::BrandsCommand).bind<ShardCommand>()
  singleOf(::ConnectCommand).bind<ShardCommand>()
  singleOf(::CollectCommand).bind<ShardCommand>()
  singleOf(::EditorCommand).bind<ShardCommand>()
  singleOf(::MitigationsCommand).bind<ShardCommand>()
  singleOf(::SetupCommand).bind<ShardCommand>()
  singleOf(::ExemptCommand).bind<ShardCommand>()
  singleOf(::PunishCommand).bind<ShardCommand>()
  singleOf(::BufferCommand).bind<ShardCommand>()
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
  singleOf(::PunishmentManager)
  singleOf(::InferenceServices)
  singleOf(::ClientBrandHandler)
  singleOf(::ShardPlayerFactory)
  singleOf(::PlayerThreadRefreshListener)
  singleOf(::PlayerDirectory)
  singleOf(::TargetResolver)
}
