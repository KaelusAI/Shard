import net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission
import org.gradle.api.file.DuplicatesStrategy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import tasks.PrintFilePathTask
import versioning.BuildConfig

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.4.10"
  id("com.gradleup.shadow") version "9.6.1"
  id("net.minecrell.plugin-yml.bukkit") version "0.6.0"
  id("com.diffplug.spotless") version "8.8.0"
  id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

BuildConfig.init(project)

group = "ac.shard"

version = (findProperty("shardVersion") as? String)?.takeIf { it.isNotBlank() } ?: "1.2.1"

val packetEventsSpigot = "com.github.retrooper:packetevents-spigot:2.13.0"

repositories {
  mavenCentral()
  maven("https://jitpack.io")
  maven("https://repo.papermc.io/repository/maven-public/")
  maven("https://repo.codemc.io/repository/maven-releases/")
  maven("https://repo.codemc.io/repository/maven-snapshots/")
  maven("https://maven.enginehub.org/repo/") // WorldGuard
  maven("https://repo.extendedclip.com/releases/") // PlaceholderAPI
  maven("https://repo.opencollab.dev/maven-snapshots/") // Geyser / Floodgate
}

dependencies {
  // Bukkit APIs
  compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
  compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.17")
  compileOnly("me.clip:placeholderapi:2.12.3")
  compileOnly("org.geysermc.floodgate:api:2.0-SNAPSHOT")

  // PacketEvents
  if (BuildConfig.shadePE) {
    implementation(packetEventsSpigot)
  } else {
    compileOnly(packetEventsSpigot)
  }
  implementation("org.bstats:bstats-bukkit:3.2.1")

  // Cloud Command Framework
  implementation("org.incendo:cloud-paper:2.0.0")
  implementation("org.incendo:cloud-processors-requirements:1.0.0-rc.1")
  implementation("org.incendo:cloud-kotlin-extensions:2.1.0")
  implementation("org.incendo:cloud-kotlin-coroutines:2.1.0")

  // Adventure & MiniMessage
  implementation("net.kyori:adventure-platform-bukkit:4.4.1")
  implementation("net.kyori:adventure-text-minimessage:4.26.1")
  implementation("net.kyori:adventure-text-serializer-plain:4.26.1")
  implementation("net.kyori:adventure-text-serializer-gson:4.26.1")

  // HikariCP
  implementation("com.zaxxer:HikariCP:7.1.0")
  implementation("org.slf4j:slf4j-jdk14:2.0.18")
  implementation("org.jetbrains.exposed:exposed-core:1.3.1")
  implementation("org.jetbrains.exposed:exposed-java-time:1.3.1")
  implementation("org.jetbrains.exposed:exposed-jdbc:1.3.1")
  implementation("org.flywaydb:flyway-core:12.1.1")
  implementation("org.flywaydb:flyway-mysql:12.1.1")
  implementation("org.mariadb.jdbc:mariadb-java-client:3.5.9")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

  // Redis (cross-server alerts). Netty stays unbundled and unrelocated: PacketEvents reflects on
  // the server's io.netty Channel type, so Lettuce must share the server's Netty.
  implementation("io.lettuce:lettuce-core:6.5.0.RELEASE") { exclude(group = "io.netty") }
  compileOnly("io.netty:netty-handler:4.1.113.Final")

  // Utilities
  implementation(kotlin("stdlib"))
  implementation("it.unimi.dsi:fastutil:8.5.18")
  implementation("org.jetbrains:annotations:26.1.0")
  implementation("org.spongepowered:configurate-yaml:4.2.0")
  implementation("ru.vyarus:yaml-config-updater:1.4.4")
  implementation("io.insert-koin:koin-core:4.2.2")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.1")

  // Testing
  testImplementation(kotlin("test"))
  testImplementation(packetEventsSpigot)
  testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
  testImplementation("io.mockk:mockk:1.14.11")
  testImplementation("org.testcontainers:junit-jupiter:1.21.4")
  testImplementation("org.testcontainers:mariadb:1.21.4")
  testCompileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
  testRuntimeOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
  testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.2.1")
  testRuntimeOnly("io.netty:netty-handler:4.1.113.Final")
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(21))
  disableAutoTargetJvm()
}

kotlin { jvmToolchain(21) }

tasks.withType<JavaCompile> {
  options.release.set(17)
  options.encoding = "UTF-8"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    freeCompilerArgs.addAll("-jvm-default=enable")
  }
}

tasks.jar { archiveClassifier.set("thin") }

tasks.shadowJar {
  archiveBaseName.set(rootProject.name)
  archiveClassifier.set("")
  if (!BuildConfig.shadePE) {
    archiveFileName.set("${rootProject.name}-${project.version}.unbundled.jar")
  }

  eachFile {
    if (path == "META-INF/services/org.flywaydb.core.extensibility.Plugin") {
      duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
  }

  minimize {
    exclude(dependency("org.slf4j:slf4j-api"))
    exclude(dependency("org.slf4j:slf4j-jdk14"))
    exclude(dependency("org.jetbrains.exposed:exposed-core"))
    exclude(dependency("org.jetbrains.exposed:exposed-jdbc"))
    exclude(dependency("org.jetbrains.exposed:exposed-java-time"))
    exclude(dependency("org.flywaydb:flyway-core"))
    exclude(dependency("org.flywaydb:flyway-mysql"))
    exclude(dependency("org.mariadb.jdbc:mariadb-java-client"))
    exclude(dependency("io.lettuce:lettuce-core"))
    exclude(dependency("io.projectreactor:reactor-core"))
    exclude(dependency("org.reactivestreams:reactive-streams"))
    exclude(dependency("net.kyori:adventure-text-serializer-gson:.*"))
  }

  mergeServiceFiles()

  if (BuildConfig.shadePE) {
    relocate("com.github.retrooper.packetevents", "ac.shard.libs.packetevents.api")
    relocate("io.github.retrooper.packetevents", "ac.shard.libs.packetevents.impl")
    relocate("net.kyori", "ac.shard.libs.kyori")
  }
  relocate("org.bstats", "ac.shard.libs.bstats")
  relocate("org.incendo", "ac.shard.libs.incendo")
  relocate("io.leangen.geantyref", "ac.shard.libs.geantyref")
  relocate("it.unimi.dsi.fastutil", "ac.shard.libs.fastutil")
  relocate("com.fasterxml.jackson", "ac.shard.libs.jackson")
  relocate("com.zaxxer", "ac.shard.libs.hikari")
  relocate("org.slf4j", "ac.shard.libs.slf4j")
  relocate("org.jetbrains.exposed", "ac.shard.libs.jetbrains.exposed")
  relocate("org.spongepowered.configurate", "ac.shard.libs.configurate")
  relocate("org.yaml.snakeyaml", "ac.shard.libs.snakeyaml")
  relocate("ru.vyarus.yaml.updater", "ac.shard.libs.yamlupdater")
  relocate("org.joml", "ac.shard.libs.joml")
  relocate("org.koin", "ac.shard.libs.koin")
  relocate("org.flywaydb", "ac.shard.libs.flyway")
  relocate("tools.jackson", "ac.shard.libs.tools.jackson")
  relocate("io.lettuce", "ac.shard.libs.lettuce")
  relocate("reactor", "ac.shard.libs.reactor")
  relocate("org.reactivestreams", "ac.shard.libs.reactivestreams")
}

tasks.register<PrintFilePathTask>("printShadowJarPath") {
  description = "Prints the absolute path of the release shadow JAR."
  group = "help"
  file.set(tasks.shadowJar.flatMap { it.archiveFile })
}

tasks.test {
  useJUnitPlatform { excludeTags("container") }
  jvmArgs(
    "-XX:+EnableDynamicAgentLoading",
    "--add-opens",
    "java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens",
    "java.base/java.lang=ALL-UNNAMED",
  )
}

val containerTest by
  tasks.registering(Test::class) {
    description = "Runs container-backed integration tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("container") }
    shouldRunAfter(tasks.test)
    jvmArgs(
      "-XX:+EnableDynamicAgentLoading",
      "--add-opens",
      "java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens",
      "java.base/java.lang=ALL-UNNAMED",
    )
  }

tasks.build { dependsOn(tasks.shadowJar) }

detekt {
  toolVersion = "1.23.8"
  buildUponDefaultConfig = true
  allRules = false
  parallel = true
  baseline = file("config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
  jvmTarget = "17"
  exclude("**/build/**")
}

bukkit {
  name = "Shard"
  main = "ac.shard.Shard"
  version = project.version.toString()
  apiVersion = "1.13"
  authors = listOf("Kaelus")
  website = "https://discord.gg/kaelus"
  foliaSupported = true
  if (!BuildConfig.shadePE) {
    depend = listOf("packetevents")
  }
  softDepend =
    listOf(
      "ProtocolLib",
      "ProtocolSupport",
      "Essentials",
      "ViaVersion",
      "ViaBackwards",
      "ViaRewind",
      "Geyser-Spigot",
      "floodgate",
      "FastLogin",
      "PlaceholderAPI",
      "WorldGuard",
    )

  permissions {
    register("shard.help") {
      description = "Allows usage of the help command"
      default = Permission.Default.OP
    }
    register("shard.alerts") {
      description = "Receive alerts for violations"
      default = Permission.Default.OP
    }
    register("shard.alerts.enable-on-join") {
      description = "Automatically enables alerts on join"
      default = Permission.Default.OP
    }
    register("shard.reload") {
      description = "Allows reloading the config"
      default = Permission.Default.OP
    }
    register("shard.connect") {
      description = "Allows linking/unlinking this server to the Shard web panel"
      default = Permission.Default.OP
    }
    register("shard.exempt") {
      description = "Exempt from all checks"
      default = Permission.Default.FALSE
    }
    register("shard.disable") {
      description = "Disables anti-cheat tracking for the player"
      default = Permission.Default.FALSE
    }
    register("shard.datacollect") {
      description = "Parent permission for data collection commands"
      default = Permission.Default.OP
      children =
        listOf(
          "shard.datacollect.start",
          "shard.datacollect.stop",
          "shard.datacollect.cancel",
          "shard.datacollect.status",
        )
    }
    register("shard.datacollect.start") {
      description = "Allows starting a data collection session"
      default = Permission.Default.FALSE
    }
    register("shard.datacollect.stop") {
      description = "Allows stopping a data collection session"
      default = Permission.Default.FALSE
    }
    register("shard.datacollect.cancel") {
      description = "Allows cancelling a data collection session without saving"
      default = Permission.Default.FALSE
    }
    register("shard.datacollect.status") {
      description = "Allows viewing data collection session status"
      default = Permission.Default.FALSE
    }
    register("shard.monitor") {
      description = "Allows usage of the monitor command"
      default = Permission.Default.OP
      children =
        listOf(
          "shard.monitor.self",
          "shard.monitor.list",
          "shard.monitor.others",
          "shard.monitor.multi",
          "shard.monitor.output",
        )
    }
    register("shard.monitor.others") {
      description = "Allows monitoring players other than yourself"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.multi") {
      description = "Allows watching more than one player at the same time"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.output") {
      description = "Parent permission for monitor output selection"
      default = Permission.Default.OP
      children =
        listOf(
          "shard.monitor.output.actionbar",
          "shard.monitor.output.bossbar",
          "shard.monitor.output.sidebar",
          "shard.monitor.output.chat",
          "shard.monitor.output.tablist",
        )
    }
    register("shard.monitor.output.actionbar") {
      description = "Allows using the action bar monitor output"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.output.bossbar") {
      description = "Allows using the boss bar monitor output"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.output.sidebar") {
      description = "Allows using the scoreboard sidebar monitor output"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.output.chat") {
      description = "Allows using the chat monitor output"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.output.tablist") {
      description = "Allows using the tab list monitor output"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.self") {
      description = "Allows enabling the monitor display only on self"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.list") {
      description = "Allows listing active monitor sessions"
      default = Permission.Default.OP
    }
    register("shard.prob") {
      description = "Legacy alias for shard.monitor"
      default = Permission.Default.FALSE
      children = listOf("shard.monitor")
    }
    register("shard.prob.self") {
      description = "Legacy alias for shard.monitor.self"
      default = Permission.Default.FALSE
      children = listOf("shard.monitor.self")
    }
    register("shard.prob.list") {
      description = "Legacy alias for shard.monitor.list"
      default = Permission.Default.FALSE
      children = listOf("shard.monitor.list")
    }
    register("shard.view") {
      description = "Allows toggling the AI nametag view on players"
      default = Permission.Default.OP
    }
    register("shard.profile") {
      description = "Allows usage of the profile command"
      default = Permission.Default.OP
    }
    register("shard.brand") {
      description = "Receive client brand notifications"
      default = Permission.Default.OP
    }
    register("shard.brand.enable-on-join") {
      description = "Automatically enables brand notifications on join"
      default = Permission.Default.OP
    }
    register("shard.history") {
      description = "Allows viewing a player's violation history"
      default = Permission.Default.OP
    }
    register("shard.logs") {
      description = "Allows viewing recent violations"
      default = Permission.Default.OP
    }
    register("shard.stats") {
      description = "Allows viewing server statistics"
      default = Permission.Default.OP
    }
    register("shard.exempt.manage") {
      description = "Allows managing punishment exemptions for players"
      default = Permission.Default.OP
    }
    register("shard.punish.manage") {
      description = "Allows managing player punishments"
      default = Permission.Default.OP
    }
    register("shard.suspicious") {
      description = "Permission for suspicious player commands"
      default = Permission.Default.OP
      children =
        listOf(
          "shard.suspicious.alerts",
          "shard.suspicious.list",
          "shard.suspicious.top",
          "shard.suspicious.flagged",
        )
    }
    register("shard.suspicious.alerts") {
      description = "Allows toggling suspicious player alerts"
      default = Permission.Default.OP
    }
    register("shard.suspicious.alerts.enable-on-join") {
      description = "Automatically enables suspicious alerts on join"
      default = Permission.Default.OP
    }
    register("shard.suspicious.list") {
      description = "Allows listing suspicious players"
      default = Permission.Default.OP
    }
    register("shard.suspicious.top") {
      description = "Allows viewing the top suspicious player"
      default = Permission.Default.OP
    }
    register("shard.suspicious.flagged") {
      description = "Allows viewing online players with recorded flags"
      default = Permission.Default.OP
    }

    listOf(
        "help",
        "alerts",
        "alerts.enable-on-join",
        "reload",
        "connect",
        "exempt",
        "exempt.manage",
        "disable",
        "datacollect",
        "datacollect.start",
        "datacollect.stop",
        "datacollect.cancel",
        "datacollect.status",
        "prob",
        "prob.self",
        "prob.list",
        "monitor",
        "monitor.self",
        "monitor.list",
        "monitor.others",
        "monitor.multi",
        "monitor.output",
        "monitor.output.actionbar",
        "monitor.output.bossbar",
        "monitor.output.sidebar",
        "monitor.output.chat",
        "monitor.output.tablist",
        "view",
        "profile",
        "brand",
        "brand.enable-on-join",
        "history",
        "logs",
        "stats",
        "punish.manage",
        "suspicious",
        "suspicious.alerts",
        "suspicious.alerts.enable-on-join",
        "suspicious.list",
        "suspicious.top",
        "suspicious.flagged",
      )
      .forEach { node ->
        register("sloth.$node") {
          description = "Legacy alias for shard.$node"
          default = Permission.Default.FALSE
          children = listOf("shard.$node")
        }
      }
  }
}

spotless {
  isEnforceCheck = true

  kotlin {
    target("src/**/*.kt")
    ktfmt().googleStyle()
  }

  kotlinGradle {
    target("*.gradle.kts")
    ktfmt().googleStyle()
  }
}
