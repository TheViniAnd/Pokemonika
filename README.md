# Pokemonika Free

Pokemon catching and turn-based wild battles for **Paper 26.3 build 230, Java 25**. Uses vanilla block models; no client mods or resource pack required. Source code: MIT.

Includes 10 Pokemon, 40 distinct moves, a standard Poke Ball, a party of six, 45 storage slots, a basic Pokedex, XP and healing. Pokemon: Pikachu, Charmander, Bulbasaur, Squirtle, Eevee, Magikarp, Slowpoke, Pidgey, Psyduck and Vulpix.

## Install

1. Build with `./gradlew test jar` (Windows: `gradlew.bat test jar`).
2. Place `build/libs/Pokemonika-Free-2.2.0.jar` in the server's `plugins` folder.
3. Start the server yourself and run `/pokemonika starter`.

Install one Pokemonika JAR. Free uses `plugins/Pokemonika`; do not point it at Premium data. An existing Premium folder is rejected to prevent data loss.

## Language

English is the default. To use Russian, change `plugins/Pokemonika/config.yml`:

```yaml
language: ru
```

Then run `/pokemonika admin reload`. Both message files can be edited; keep their placeholder names.

## Commands

| Command | Action |
|---|---|
| `/pokemonika` | Party or current battle |
| `/pokemonika starter` | Choose your starter once |
| `/pokemonika heal` | Restore HP and PP outside battle |
| `/pokemonika balls` | Poke Ball recipe |
| `/pc`, `/dex` | Storage and Pokedex |
| `/pokemonika admin give <player> <species> <level>` | Grant a Pokemon |
| `/pokemonika admin spawn <species> <level>` | Spawn a wild Pokemon safely |
| `/pokemonika admin balls <player> <amount>` | Queue Poke Balls |
| `/pokemonika admin clear/reload/stats` | Administration |

Players have `pokemonika.play` by default; administration requires `pokemonika.admin`. Legacy `vanillamon.*` permission names and `/vm`, `/pk`, `/creatures` aliases remain supported.

Pokemon HP is managed by the plugin. Vanilla damage cannot hit models or interfere with participants during a Pokemon battle; ordinary PvP outside those battles is unchanged. Aquatic Pokemon need water. Creature and particle limits apply independently of catalogue size.

## Premium and upgrades

Premium is distributed separately by the author through Boosty. It adds the full catalogue and advanced systems. This repository contains only Free code and assets.

To upgrade, stop your server, back up `plugins/Pokemonika`, replace the Free JAR with Premium, and keep the data folder. Shared Pokemon IDs, collections, HP, PP, XP and pending deliveries are preserved. Premium-to-Free downgrade is unsupported and explicitly rejected.

## Development

`./gradlew test jar` builds without launching Minecraft. Tests cover storage, capture/victory receipts, animation, spawning, combat protection and EN/RU packs. In-game verification remains necessary for a server's installed plugin set.

Dependency and data credits: [THIRD_PARTY.md](THIRD_PARTY.md). Russian instructions: [README.ru.md](README.ru.md).
