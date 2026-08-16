<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/assets/banner-dark.svg">
    <img alt="Shard" src=".github/assets/banner-light.svg" width="420">
  </picture>

  <p>An AI-powered, free and open-source anti-cheat for Minecraft servers.</p>

  <p>
    <a href="README.md"><b>English</b></a>
    ·
    <a href="README.ru.md">Русский</a>
    ·
    <a href="https://shard.ac">shard.ac</a>
    ·
    <a href="https://app.shard.ac">Panel</a>
    ·
    <a href="https://discord.gg/kaelus">Discord</a>
  </p>

  <p>
    <a href="https://github.com/KaelusAI/Shard/actions/workflows/ci.yml">
      <img alt="CI" src="https://github.com/KaelusAI/Shard/actions/workflows/ci.yml/badge.svg">
    </a>
    <a href="https://app.codacy.com/gh/KaelusAI/Shard/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade">
      <img alt="Codacy" src="https://app.codacy.com/project/badge/Grade/fb90bca03b36460faab2cf86fc2178bd?branch=main">
    </a>
    <a href="https://discord.gg/kaelus">
      <img alt="Discord" src="https://img.shields.io/discord/1297490292349468715?style=flat&label=Discord&logo=discord&color=7289DA&logoColor=white">
    </a>
    <a href="https://github.com/KaelusAI/Shard/">
      <img alt="Views" src="https://api.visitorbadge.io/api/visitors?path=https%3A%2F%2Fgithub.com%2FKaelusMC%2FSlothAC%2FREADME.md&label=Views&countColor=%23555555&style=flat&labelStyle=none">
    </a>
  </p>
</div>

## What Shard is

Shard is an open-source combat anti-cheat for Minecraft servers. An AI model checks the player's mouse movement and detects killaura and aim assist.

## Important before you install

Shard's AI check uses the official Shard API. Create an account at [app.shard.ac](https://app.shard.ac) to get access.

Two models are available, and you pick one in the panel:

- **Flash** - on every plan including the free tier, no verification required
- **Pro** - detects more cheats, at a higher price

To connect a server to the API, run `/shard connect` and authorize it in the panel.

If API access is not available yet, disable the AI check for now.

## Requirements

- Java 17 or newer to run the plugin
- JDK 21 or newer if you want to build from source
- A Paper or Folia-based server

## Installation

1. Download the latest release from [GitHub Releases](https://github.com/KaelusAI/Shard/releases).
2. Place the main `Shard-<version>.jar` in the server `plugins/` directory.
3. Start the server once so Shard can generate its configuration files.
4. Run `/shard connect` and authorize the server in the panel.
5. If needed, configure storage:
   - SQLite is the default
   - MySQL and MariaDB are also supported
6. If WorldGuard is installed, specific regions can be excluded from the AI check.
7. Restart the server or reload the Shard configuration.

## Configuration files

- [`config.yml`](src/main/resources/config.yml): AI, database, Redis and cross-server alerts, alerts, duplicate packet handling
- [`monitor.yml`](src/main/resources/monitor.yml): outputs, limits, themes and formatting for `/shard monitor` and `/shard view`
- [`punishments.yml`](src/main/resources/punishments.yml): punishment rules
- [`messages/messages_en.yml`](src/main/resources/messages/messages_en.yml): English messages
- [`messages/messages_ru.yml`](src/main/resources/messages/messages_ru.yml): Russian messages

## Main commands

| Command | Purpose |
| --- | --- |
| `/shard connect` | Link this server to the panel |
| `/shard connect status` | Show the panel connection status |
| `/shard disconnect` | Unlink this server from the panel |
| `/shard alerts` | Toggle violation alerts |
| `/shard suspicious <list\|top\|flagged>` | Review suspicious or previously flagged online players |
| `/shard profile <player>` | Open a player's live profile |
| `/shard monitor <player>` | Watch AI data for one player in real time |
| `/shard monitor add\|remove\|clear` | Watch several players at once |
| `/shard monitor output <type>` | Draw the monitor on the action bar, boss bar, sidebar, chat or tab list |
| `/shard monitor settings` | Show and edit your monitor settings |
| `/shard view` | Toggle observation mode for nearby players |
| `/shard logs [page]` | View recent violations |
| `/shard history <player> [page]` | View a player's stored violation history |
| `/shard stats` | View server-side anti-cheat stats |
| `/shard dc <start\|stop\|cancel\|status>` | Manage labeled data collection sessions |
| `/shard reload` | Reload Shard configuration |

For the full command list, use `/shard help` in game.

## Building from source

```bash
git clone https://github.com/KaelusAI/Shard.git
cd Shard
./gradlew shadowJar
```

The main plugin jar lands in `build/libs/Shard-<version>.jar`.

## Help, bugs, and discussion

- Bug reports: [GitHub Issues](https://github.com/KaelusAI/Shard/issues)
- Community / support: [Discord](https://discord.gg/kaelus)

Issue reports should include:

- server version
- Java version
- plugin version
- relevant config values
- logs, stack traces, and steps to reproduce

## Credits

Shard has its own, independently developed codebase. That said, some parts of its code are adapted from the open-source [GrimAC](https://github.com/GrimAnticheat/Grim) project, and Shard builds on ideas developed by GrimAC, DefineOutside, and other GrimAC contributors - full credit and thanks to them for their work.

## License

Shard is distributed under the terms of the [GNU General Public License v3.0](LICENSE).

<img src="https://api.visitorbadge.io/api/visitors?path=shard-ac&label=&countColor=%23555555&style=flat&labelStyle=none" alt="" width="1" height="1">

