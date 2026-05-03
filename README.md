<p align="center">
  <h1 align="center">⚔️ T-Kits</h1>
  <p align="center">
    <strong>A high-performance kit management suite for Paper 1.21.x</strong>
  </p>
  <p align="center">
    <a href="https://github.com/takedaa83/T-Kits/actions"><img src="https://img.shields.io/github/actions/workflow/status/takedaa83/T-Kits/maven.yml?style=flat-square&label=Build" alt="Build Status"></a>
    <img src="https://img.shields.io/badge/Minecraft-1.21.x-brightgreen?style=flat-square" alt="Minecraft Version">
    <img src="https://img.shields.io/badge/Java-21+-blue?style=flat-square" alt="Java Version">
    <img src="https://img.shields.io/badge/Platform-Paper-blue?style=flat-square" alt="Platform">
    <img src="https://img.shields.io/badge/Version-1.1-purple?style=flat-square" alt="Plugin Version">
    <img src="https://img.shields.io/badge/License-All%20Rights%20Reserved-red?style=flat-square" alt="License">
  </p>
</p>

---

## Overview

**T-Kits** is a production-grade kit management plugin purpose-built for competitive Minecraft PvP servers running Paper 1.21.x. It delivers a complete GUI-based kit editing workflow, one-click inventory regearing, cross-player kit sharing, a server-wide categorized kitroom, and a full developer API — all powered by non-blocking async storage with support for both YAML and MySQL backends.

### Features

| Feature | Description |
|---------|-------------|
| **GUI Kit Editor** | Full drag-and-drop kit editor with armor slots, ender chest support, and stack-based navigation history |
| **Async Storage** | All I/O via `CompletableFuture` with Java 21 virtual threads. YAML and MySQL with zero-downtime live migration |
| **Regear & Arrange** | Replenish consumed items from your kit template, or re-sort your inventory to match kit layout |
| **Kit Sharing** | Generate temporary one-time-use codes to share kit configurations between players |
| **Kitroom** | Categorized item repository managed via in-game admin GUI. Players click items to take copies |
| **Combat Tag** | Configurable combat tagging with command blocking, ender pearl prevention, and bypass permissions |
| **Developer API** | 14-method public API registered via Bukkit `ServicesManager` for third-party integrations |
| **PlaceholderAPI** | Expose kit status, combat tag state, and player data to scoreboards and chat plugins |

---

## Getting Started

### Requirements

| Component | Version |
|-----------|---------|
| Server | Paper 1.21.x (or compatible forks: Purpur, Folia) |
| Java | 21+ |
| Optional | PlaceholderAPI, MySQL 8.0+ |

### Installation

1. Download or build: `mvn clean package`
2. Place `T-Kits-1.1.jar` into `plugins/`
3. Start the server — config files generate automatically
4. Configure `plugins/T-Kits/config.yml` to your needs

### MySQL Setup

```yaml
# config.yml
storage:
  type: MYSQL
  mysql:
    host: "localhost"
    port: 3306
    database: "tkits"
    username: "your_user"
    password: "your_password"
```

Migrate existing data: `/tkitsadmin migrate yaml mysql`

---

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/kit` | `tkits.use` | Open the main kit management GUI |
| `/kit load <N>` | `tkits.use` | Load a specific kit by number |
| `/kit share` | `tkits.kit.share` | Generate a share code for your current kit |
| `/kit import <code>` | `tkits.kit.share` | Import a kit from a share code |
| `/k1` – `/k7` | `tkits.load.<N>` | Quick-load kit shortcuts |
| `/regear` · `/rg` | `tkits.regear` | Get a Regear Shulker Box to replenish items |
| `/arrange` · `/ag` | `tkits.arrange` | Re-sort inventory to match kit layout |
| `/tkitsadmin reload` | `tkits.admin` | Reload all configuration files |
| `/tkitsadmin migrate <from> <to>` | `tkits.admin` | Live-migrate data between storage backends |
| `/tkitsadmin kitroom` | `tkits.kitroom.admin` | Open the kitroom editor |

---

## Permissions

| Permission | Default | Description |
|-----------|---------|-------------|
| `tkits.use` | `true` | Main `/kit` command and GUI access |
| `tkits.load.*` | `true` | All quick-load shortcuts |
| `tkits.regear` | `true` | `/regear` command |
| `tkits.arrange` | `true` | `/arrange` command |
| `tkits.kit.share` | `true` | Share and import kits |
| `tkits.kit.global` | `true` | Set kits as globally visible |
| `tkits.kitroom.use` | `true` | Take items from the kitroom |
| `tkits.admin` | `op` | Administrative commands |
| `tkits.kitroom.admin` | `op` | Edit kitroom layouts |
| `tkits.cooldown.bypass` | `op` | Bypass all cooldown timers |
| `tkits.combattag.bypass` | `op` | Bypass combat tag restrictions |

---

## Configuration

T-Kits generates three configuration files:

| File | Purpose |
|------|---------|
| `config.yml` | Storage backend, cooldowns, combat tag, sounds, messages, kit limits |
| `gui.yml` | GUI titles, button materials, names, lore, Custom Model Data, slot positions |
| `kitroom.yml` | Kitroom item data (managed via in-game GUI — no manual editing required) |

<details>
<summary><b>Key config.yml options</b></summary>

```yaml
storage:
  type: YAML  # or MYSQL

kits:
  max_kits_per_player: 9
  save_on_editor_close: true
  clear_kit_requires_confirmation: true
  prevent_save_items: [BEDROCK, COMMAND_BLOCK]

sharing:
  code_length: 5
  code_one_time_use: true
  code_expiration_minutes: 5

combat_tag:
  enabled: true
  duration_seconds: 10
  blocked_commands: ["/k1", "/k2", "/regear", "/spawn"]
  prevent_enderpearl: false

cooldowns:
  kit_load: 3
  regear: 10
  arrange: 5
```

</details>

---

## Developer API

T-Kits provides a public API via Bukkit's `ServicesManager`:

```java
RegisteredServiceProvider<TKitsAPI> provider =
    Bukkit.getServicesManager().getRegistration(TKitsAPI.class);

if (provider != null) {
    TKitsAPI api = provider.getProvider();

    // Async kit retrieval (recommended)
    api.getPlayerKitAsync(playerUUID, 1).thenAccept(optKit -> {
        optKit.ifPresent(kit -> /* use kit */);
    });

    // Apply kit, regear, arrange
    api.loadKitOntoPlayer(player, 1);
    api.performRegear(player);
    api.performArrange(player);

    // Combat tag check
    if (api.isPlayerCombatTagged(player)) {
        long remaining = api.getRemainingCombatTagMillis(player);
    }

    // Open kit selection GUI (returns future)
    api.choosePersonalKit(player, title).thenAccept(optKit -> {
        optKit.ifPresent(selectedKit -> /* player chose a kit */);
    });
}
```

<details>
<summary><b>Full API reference</b></summary>

| Method | Description |
|--------|-------------|
| `getPlayerKit(UUID, int)` | Get cached kit synchronously |
| `getPlayerKitAsync(UUID, int)` | Get kit from storage asynchronously |
| `getAllPlayerKits(UUID)` | Get all kits for a player |
| `getAllPlayerKitsAsync(UUID)` | Get all kits asynchronously |
| `loadKitOntoPlayer(Player, int)` | Apply a kit to a player's inventory |
| `getLastLoadedKitNumber(Player)` | Get the last loaded kit number |
| `getCurrentLoadedKit(Player)` | Get the Kit object of the last loaded kit |
| `getGlobalKits()` | List all global kits |
| `isPlayerCombatTagged(Player)` | Check if player is combat tagged |
| `getRemainingCombatTagMillis(Player)` | Get remaining combat tag duration |
| `getRegearTriggerItem()` | Get the regear shulker box ItemStack |
| `performRegear(Player)` | Trigger regear for a player |
| `performArrange(Player)` | Trigger arrange for a player |
| `choosePersonalKit(Player, Component)` | Open kit selection GUI with future result |

</details>

---

## PlaceholderAPI

| Placeholder | Output |
|------------|--------|
| `%tkits_last_loaded_kit%` | `3` or `None` |
| `%tkits_combat_tagged%` | `Yes` / `No` |
| `%tkits_combat_tag_time%` | `4.2s` |
| `%tkits_kit_<N>_exists%` | `Yes` / `No` |
| `%tkits_kit_<N>_is_global%` | `Yes` / `No` |

---

## Architecture

```
com.takeda.tkits
├── TKits.java                          # Plugin lifecycle & dependency wiring
├── api/                                # Public API (TKitsAPI interface + impl)
├── commands/                           # ACF-based command handlers
├── config/ConfigManager.java           # Multi-file config loading & caching
├── listeners/                          # Event handlers (GUI, interactions, player lifecycle)
├── managers/
│   ├── GuiManager.java                 # GUI creation, navigation, state tracking
│   ├── KitManager.java                 # Kit CRUD, loading, global cache
│   ├── KitroomManager.java            # Category-based item repository
│   ├── PlayerDataManager.java         # Async player data load/save/cache
│   ├── CombatTagManager.java          # Combat detection & command blocking
│   └── ShareCodeManager.java          # Temporary code generation & redemption
├── models/                             # Data models (Kit, KitContents, PlayerData)
├── services/                           # Cooldown & utility services
├── storage/                            # StorageHandler interface + YAML/MySQL backends
└── util/                               # MessageUtil, GuiUtils, ItemBuilder, ItemSerialization
```

### Design Principles

- **Non-blocking I/O** — All storage operations return `CompletableFuture`; virtual threads (Java 21) handle executor pools
- **State-driven GUIs** — `Deque<GuiState>` per player enables back-button navigation; PDC-based action system makes click handling data-driven
- **Graceful degradation** — Corrupted items are skipped during serialization; failed loads return empty PlayerData instead of crashing
- **Batched persistence** — MySQL migrations use JDBC batch operations for 10× throughput; GUI item templates are cached on reload

---

## Building from Source

```bash
git clone https://github.com/takedaa83/T-Kits.git
cd T-Kits
mvn clean package
# Output: target/T-Kits-1.1.jar
```

**Build requirements:** JDK 21+, Maven 3.8+

### Dependencies

| Library | Version | Packaging |
|---------|---------|-----------|
| [ACF](https://github.com/aikar/commands) | 0.5.1-SNAPSHOT | Shaded |
| [AnvilGUI](https://github.com/WesJD/AnvilGUI) | 1.10.12-SNAPSHOT | Shaded |
| [Commons Lang3](https://commons.apache.org/proper/commons-lang/) | 3.14.0 | Shaded |
| Paper API | 1.21.x | Provided |
| [Lombok](https://projectlombok.org/) | 1.18.46 | Compile-only |
| [PlaceholderAPI](https://placeholderapi.com/) | 2.11.5 | Optional |
| [HikariCP](https://github.com/brettwooldridge/HikariCP) | 6.3.0 | Provided (MySQL only) |

---

## License

All rights reserved. © Takeda_Dev

---

<p align="center">
  <sub>Built for competitive Minecraft PvP servers</sub>
</p>
