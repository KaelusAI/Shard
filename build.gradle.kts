import net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission
import org.gradle.api.file.DuplicatesStrategy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import tasks.PrintFilePathTask
import versioning.BuildConfig

plugins {
  id("java")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.shadow)
  alias(libs.plugins.plugin.yml.bukkit)
  alias(libs.plugins.spotless)
  alias(libs.plugins.detekt)
}

BuildConfig.init(project)

group = "ac.shard"

version = (findProperty("shardVersion") as? String)?.takeIf { it.isNotBlank() } ?: "2.0.0"

repositories {
  mavenCentral()
  maven("https://repo.papermc.io/repository/maven-public/")
  maven("https://repo.codemc.io/repository/maven-releases/")
  maven("https://repo.codemc.io/repository/maven-snapshots/")
  maven("https://maven.enginehub.org/repo/") // WorldGuard
  maven("https://repo.opencollab.dev/maven-snapshots/") // Geyser / Floodgate
}

dependencies {
  // Bukkit APIs
  compileOnly(libs.paper.api)
  compileOnly(libs.worldguard)
  compileOnly(libs.floodgate.api)

  implementation(libs.shard.api)

  // PacketEvents
  if (BuildConfig.shadePE) {
    implementation(libs.packetevents.spigot)
  } else {
    compileOnly(libs.packetevents.spigot)
  }
  implementation(libs.bstats.bukkit)

  // Cloud Command Framework
  implementation(libs.cloud.paper)
  implementation(libs.cloud.processors.requirements)
  implementation(libs.cloud.kotlin.extensions)

  // Adventure & MiniMessage
  implementation(libs.adventure.platform.bukkit)
  implementation(libs.adventure.minimessage)
  implementation(libs.adventure.serializer.plain)
  implementation(libs.adventure.serializer.gson)

  // HikariCP
  implementation(libs.hikaricp)
  implementation(libs.slf4j.jdk14)
  implementation(libs.exposed.core)
  implementation(libs.exposed.jdbc)
  implementation(libs.flyway.core)
  implementation(libs.flyway.mysql)
  implementation(libs.mariadb)
  implementation(libs.jackson.databind)

  implementation(libs.jedis) {
    exclude(group = "com.google.code.gson")
    exclude(group = "org.slf4j")
  }

  // Utilities
  implementation(kotlin("stdlib"))
  implementation(libs.fastutil)
  implementation(libs.jetbrains.annotations)
  implementation(libs.configurate.yaml)
  implementation(libs.yaml.config.updater)
  implementation(libs.koin.core)
  implementation(libs.kotlinx.collections.immutable)

  // Testing
  testImplementation(kotlin("test"))
  testImplementation(libs.packetevents.spigot)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.mockk)
  testImplementation(libs.testcontainers.junit)
  testImplementation(libs.testcontainers.mariadb)
  testCompileOnly(libs.paper.api)
  testRuntimeOnly(libs.paper.api)
  testRuntimeOnly(libs.sqlite.jdbc)
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(21))
  disableAutoTargetJvm()
}

kotlin {
  jvmToolchain(21)
  sourceSets.main {
    kotlin.srcDir(
      if (BuildConfig.shadePE) "src/packetevents/bundled/kotlin"
      else "src/packetevents/external/kotlin"
    )
  }
}

tasks.withType<JavaCompile> {
  options.release.set(17)
  options.encoding = "UTF-8"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    freeCompilerArgs.addAll("-jvm-default=enable", "-Xjdk-release=17")
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
    exclude(dependency("ac.shard:shard-api"))
    exclude(dependency("org.slf4j:slf4j-api"))
    exclude(dependency("org.slf4j:slf4j-jdk14"))
    exclude(dependency("org.jetbrains.exposed:exposed-core"))
    exclude(dependency("org.jetbrains.exposed:exposed-jdbc"))
    exclude(dependency("org.flywaydb:flyway-core"))
    exclude(dependency("org.flywaydb:flyway-mysql"))
    exclude(dependency("org.mariadb.jdbc:mariadb-java-client"))
    exclude(dependency("redis.clients:jedis"))
    exclude(dependency("redis.clients.authentication:redis-authx-core"))
    exclude(dependency("org.apache.commons:commons-pool2"))
    exclude(dependency("org.json:json"))
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
  relocate("org.koin", "ac.shard.libs.koin")
  relocate("org.flywaydb", "ac.shard.libs.flyway")
  relocate("tools.jackson", "ac.shard.libs.tools.jackson")
  relocate("redis.clients", "ac.shard.libs.redis")
  relocate("org.apache.commons.pool2", "ac.shard.libs.pool2")
  relocate("org.json", "ac.shard.libs.json")
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
  toolVersion = libs.versions.detekt.get()
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
    register("shard.setup") {
      description = "Allows running the setup wizard"
      default = Permission.Default.OP
    }
    register("shard.editor") {
      description = "Allows opening the configuration editor"
      default = Permission.Default.OP
    }
    register("shard.editor.apply") {
      description = "Allows writing what the editor produced to the config files"
      default = Permission.Default.OP
    }
    register("shard.exempt") {
      description = "Keeps the checks running but never punishes the player"
      default = Permission.Default.FALSE
    }
    register("shard.nomitigate") {
      description = "Keeps the checks running but never mitigates the player"
      default = Permission.Default.FALSE
    }
    register("shard.mitigations") {
      description = "Allows listing who is currently mitigated and why"
      default = Permission.Default.OP
    }
    register("shard.mitigations.clear") {
      description = "Allows resetting a player's mitigation score by hand"
      default = Permission.Default.OP
    }
    register("shard.mitigations.alerts") {
      description = "Allows receiving an alert when a player starts being mitigated"
      default = Permission.Default.OP
    }
    register("shard.mitigations.alerts.enable-on-join") {
      description = "Automatically enables mitigation alerts on join"
      default = Permission.Default.OP
    }
    register("shard.disable") {
      description = "Stops every check for the player, nothing is sent to the API"
      default = Permission.Default.FALSE
    }
    register("shard.collect") {
      description = "Parent permission for data collection commands"
      default = Permission.Default.OP
      children =
        listOf(
          "shard.collect.start",
          "shard.collect.stop",
          "shard.collect.cancel",
          "shard.collect.status",
        )
    }
    register("shard.collect.start") {
      description = "Allows starting a data collection session"
      default = Permission.Default.FALSE
    }
    register("shard.collect.stop") {
      description = "Allows stopping a data collection session"
      default = Permission.Default.FALSE
    }
    register("shard.collect.cancel") {
      description = "Allows cancelling a data collection session without saving"
      default = Permission.Default.FALSE
    }
    register("shard.collect.status") {
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
          "shard.monitor.collect",
          "shard.monitor.all",
          "shard.monitor.suspicious",
          "shard.monitor.auto",
        )
    }
    register("shard.monitor.auto") {
      description = "Allows watching whoever is fighting or already suspicious"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.all") {
      description = "Allows watching every online player at once"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.suspicious") {
      description = "Allows watching the players whose AI buffer is over the threshold"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.others") {
      description = "Allows monitoring players other than yourself"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.multi") {
      description = "Allows watching more than one player at the same time"
      default = Permission.Default.FALSE
    }
    register("shard.monitor.collect") {
      description = "Shows the data collection and inference rows in the monitor"
      default = Permission.Default.OP
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
    register("shard.buffer.reset") {
      description = "Allows resetting the AI buffer of a player"
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
        "collect",
        "collect.start",
        "collect.stop",
        "collect.cancel",
        "collect.status",
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
        "monitor.all",
        "monitor.suspicious",
        "monitor.auto",
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
