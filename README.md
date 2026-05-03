<p align="center">
  <h1 align="center">⚔️ T-Kits</h1>
  <p align="center">
    <strong>A modern, high-performance kit management suite for Paper 1.21.x</strong>
  </p>
  <p align="center">
    <img src="https://img.shields.io/badge/Minecraft-1.21.x-brightgreen?style=flat-square" alt="Minecraft Version">
    <img src="https://img.shields.io/badge/Java-21+-blue?style=flat-square" alt="Java Version">
    <img src="https://img.shields.io/badge/Platform-Paper-blue?style=flat-square" alt="Platform">
    <img src="https://img.shields.io/badge/Version-1.1-purple?style=flat-square" alt="Plugin Version">
    <img src="https://img.shields.io/badge/License-All%20Rights%20Reserved-red?style=flat-square" alt="License">
  </p>
</p>

---

## 📋 Overview

**T-Kits** is a feature-rich, production-grade kit management plugin built for competitive Minecraft servers running Paper 1.21.x. It provides players with a full GUI-based kit editing system, inventory regearing, kit sharing, a server-wide kitroom, and a developer API — all backed by async storage and a configurable combat tag system.

### Key Highlights

- 🎨 **Full GUI System** — Edit kits, browse global kits, preview other players' loadouts, and manage the kitroom, all from intuitive chest-based GUIs with stack-based navigation history
- ⚡ **Async Storage** — All I/O operations are non-blocking with `CompletableFuture`. Supports both **YAML** and **MySQL** backends with live migration between them
- 🔄 **Regear & Arrange** — Instantly replenish missing/consumed items from your kit template, or snap your inventory layout to match your kit's arrangement
- 🌐 **Kit Sharing** — Generate temporary codes to share kit configurations across players
- 🏪 **Kitroom System** — A categorized item shop where admins set up item templates and players click to take
- 🔌 **Developer API** — Full public API registered via Bukkit's `ServicesManager` for third-party integrations
- ⚔️ **Combat Tag** — Built-in combat tagging with configurable blocked commands and ender pearl restrictions

---

## 📦 Installation

### Requirements

| Requirement | Version |
|-------------|---------|
| **Server** | Paper 1.21.x (or forks like Purpur) |
| **Java** | 21 or higher |
| **Optional** | PlaceholderAPI, MySQL Server |

### Setup

1. Build the plugin: `mvn clean package`
2. Place `T-Kits-1.0.6.jar` from the `target/` folder into your server's `plugins/` directory
3. Start or restart your server
4. Configuration files will auto-generate in `plugins/T-Kits/`:
   - `config.yml` — Core settings, storage, cooldowns, sounds, messages
   - `gui.yml` — GUI titles, items, layouts, and button positions
   - `kitroom.yml` — Kitroom category item data

### MySQL Setup (Optional)

To use MySQL instead of YAML flat files:

1. Create a database for T-Kits on your MySQL server
2. Edit `config.yml`:
   ```yaml
   storage:
     type: MYSQL
     mysql:
       host: "localhost"
       port: 3306
       database: "tkits"
       username: "your_user"
       password: "your_password"
   ```
3. Restart the server. Tables are created automatically.
4. To migrate existing YAML data: `/tkitsadmin migrate yaml mysql`

---

## 🎮 Commands

### Player Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/kit` | `tkits.kit` | Opens the main kit management GUI |
| `/kit load <number>` | `tkits.kit` | Loads a specific kit by number |
| `/k1` through `/k9` | `tkits.load.1` through `tkits.load.9` | Quick-load shortcuts for kits 1–9 |
| `/regear` or `/rg` | `tkits.regear` | Gives a Regear Shulker Box to replenish items |
| `/arrange` or `/ag` | `tkits.arrange` | Rearranges inventory to match your last loaded kit's layout |

### Admin Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/tkitsadmin reload` | `tkits.admin` | Reloads all config files |
| `/tkitsadmin migrate <from> <to>` | `tkits.admin` | Migrates data between YAML ↔ MySQL |
| `/tkitsadmin debug <player>` | `tkits.admin` | Shows debug info for a player's data |
| `/tkitsadmin kitroom` | `tkits.kitroom.admin` | Opens the kitroom editor GUI |

---

## 🔐 Permissions

### Core Permissions

| Permission | Default | Description |
|-----------|---------|-------------|
| `tkits.kit` | `true` | Access to the main `/kit` command and GUI |
| `tkits.load.*` | `true` | Use `/k1` through `/k9` shortcuts |
| `tkits.load.<N>` | `true` | Quick-load a specific kit number |
| `tkits.regear` | `true` | Access to `/regear` command |
| `tkits.arrange` | `true` | Access to `/arrange` command |
| `tkits.share` | `true` | Generate share codes for kits |
| `tkits.import` | `true` | Import kits using share codes |
| `tkits.global` | `op` | Set kits as global (publicly viewable) |
| `tkits.repair` | `op` | Repair items in the kit editor |

### Admin Permissions

| Permission | Default | Description |
|-----------|---------|-------------|
| `tkits.admin` | `op` | Full admin access (reload, migrate, debug) |
| `tkits.kitroom.admin` | `op` | Edit kitroom category items |
| `tkits.cooldown.bypass` | `op` | Bypass all cooldown timers |
| `tkits.kitroom.category.<name>` | — | Per-category kitroom access (if enabled in config) |

---

## 📁 Configuration

### `config.yml` — Core Plugin Settings

<details>
<summary>Click to expand key options</summary>

```yaml
# Storage backend: YAML (flat files) or MYSQL
storage:
  type: YAML

# Kit slot count, auto-save, clear confirmation
kits:
  max_kits_per_player: 9
  save_on_editor_close: true
  clear_kit_requires_confirmation: true
  prevent_save_items: [BEDROCK, COMMAND_BLOCK, ...]

# Share code settings
sharing:
  code_length: 5
  code_one_time_use: true
  code_expiration_minutes: 5

# Combat tag settings
combat_tag:
  enabled: true
  duration_seconds: 10
  blocked_commands: ["/k1", "/k2", "/regear", "/spawn", ...]
  prevent_enderpearl: false

# Cooldowns (seconds)
cooldowns:
  kit_load: 3
  regear: 10
  arrange: 5
```

</details>

### `gui.yml` — GUI Customization

All GUI titles, button materials, names, lore text, custom model data, and slot positions are fully customizable. The GUI system supports:

- Legacy `&` color codes and hex colors (`&#RRGGBB`)
- PlaceholderAPI placeholders in GUI text
- Custom Model Data on all GUI items
- Per-category material/lore overrides for kitroom buttons

### `kitroom.yml` — Kitroom Data

Stores the item layouts for each kitroom category. Managed through the in-game admin GUI — no manual editing needed.

---

## 🏗️ Architecture

### Project Structure

```
com.takeda.tkits
├── TKits.java              # Main plugin class, lifecycle, dependency wiring
├── api/
│   ├── TKitsAPI.java       # Public API interface (14 methods)
│   └── TKitsAPIImpl.java   # Implementation with thread-safety
├── commands/
│   ├── KitCommand.java     # /kit command with subcommands
│   ├── AdminCommand.java   # /tkitsadmin commands
│   ├── DirectKitLoadCommands.java  # /k1–/k9 quick-load
│   └── UtilityCommands.java       # /regear, /arrange
├── config/
│   └── ConfigManager.java  # Multi-file config loading & caching
├── listeners/
│   ├── GuiListener.java    # Inventory click/drag/close events
│   ├── InteractionListener.java  # Regear shulker box placement
│   └── PlayerListener.java       # Join/quit data lifecycle
├── managers/
│   ├── GuiManager.java     # GUI creation, navigation, state tracking (1700+ lines)
│   ├── KitManager.java     # Kit CRUD, apply, global cache
│   ├── KitroomManager.java # Category-based item repository
│   ├── PlayerDataManager.java  # Async player data load/save/cache
│   ├── CombatTagManager.java   # Combat detection & command blocking
│   └── ShareCodeManager.java   # Temporary code generation & redemption
├── models/
│   ├── Kit.java            # Kit data model (Lombok @Data + @Builder)
│   ├── KitContents.java    # Base64 item serialization/deserialization
│   └── PlayerData.java     # Per-player kit collection + state
├── placeholders/
│   └── TKitsPlaceholderExpansion.java  # PlaceholderAPI integration
├── services/
│   ├── CooldownService.java    # Guava-cache based cooldown management
│   └── UtilityService.java     # Regear/arrange execution logic
├── storage/
│   ├── StorageHandler.java     # Storage interface (8 methods)
│   ├── YamlStorageHandler.java # Flat-file YAML backend
│   └── MySQLStorageHandler.java # HikariCP-pooled MySQL backend
└── util/
    ├── MessageUtil.java    # Color codes, PAPI, sound playing, logging
    ├── GuiUtils.java       # GUI item creation helpers
    ├── ItemBuilder.java    # Fluent item construction
    └── ItemSerialization.java  # Base64 item storage for kitroom
```

### Key Design Patterns

- **Async-First Storage:** All `StorageHandler` methods return `CompletableFuture` to prevent main-thread blocking
- **Stack-Based GUI Navigation:** `GuiManager` maintains a `Deque<GuiState>` per player for back-button history
- **PDC Action System:** GUI button actions are stored in `PersistentDataContainer` metadata, making click handling data-driven
- **Guava Cache Expiration:** Combat tags, cooldowns, and share codes all use TTL-based `Cache` instances for automatic cleanup
- **Blocking Shutdown Save:** `PlayerDataManager.saveAllPlayerDataBlocking()` waits up to 10 seconds on server stop to ensure data integrity

---

## 🔌 Developer API

T-Kits exposes a public API via Bukkit's `ServicesManager`:

```java
RegisteredServiceProvider<TKitsAPI> provider = 
    Bukkit.getServicesManager().getRegistration(TKitsAPI.class);
    
if (provider != null) {
    TKitsAPI api = provider.getProvider();
    
    // Get a player's kit
    Optional<Kit> kit = api.getPlayerKit(playerUUID, 1);
    
    // Async kit loading (recommended)
    api.getPlayerKitAsync(playerUUID, 1).thenAccept(optKit -> {
        optKit.ifPresent(k -> System.out.println("Kit has " + k.getContents().getItems().size() + " items"));
    });
    
    // Trigger regear/arrange
    api.performRegear(player);
    api.performArrange(player);
    
    // Check combat status
    boolean inCombat = api.isPlayerCombatTagged(player);
    
    // Open kit selection GUI (returns a future)
    api.choosePersonalKit(player, titleComponent).thenAccept(optKit -> {
        optKit.ifPresent(selectedKit -> {
            // Player chose a kit
        });
    });
}
```

### Available API Methods

| Method | Description |
|--------|-------------|
| `getPlayerKit(UUID, int)` | Get cached kit (sync, for online players) |
| `getPlayerKitAsync(UUID, int)` | Get kit from storage (async, recommended) |
| `getAllPlayerKits(UUID)` | Get all kits for a player |
| `getAllPlayerKitsAsync(UUID)` | Get all kits (async) |
| `loadKitOntoPlayer(Player, int)` | Apply a kit to a player |
| `getLastLoadedKitNumber(Player)` | Get the last loaded kit number |
| `getCurrentLoadedKit(Player)` | Get the Kit object of the last loaded kit |
| `getGlobalKits()` | List all global kits |
| `isPlayerCombatTagged(Player)` | Check combat tag status |
| `getRemainingCombatTagMillis(Player)` | Get remaining combat tag duration |
| `getRegearTriggerItem()` | Get the regear shulker box ItemStack |
| `performRegear(Player)` | Trigger regear for a player |
| `performArrange(Player)` | Trigger arrange for a player |
| `choosePersonalKit(Player, Component)` | Open kit selection GUI with future result |

---

## 📊 PlaceholderAPI Placeholders

| Placeholder | Description | Example Output |
|------------|-------------|----------------|
| `%tkits_last_loaded_kit%` | Last loaded kit number | `3` or `None` |
| `%tkits_combat_tagged%` | Whether player is in combat | `Yes` / `No` |
| `%tkits_combat_tag_time%` | Remaining combat tag time | `4.2s` |
| `%tkits_kit_<N>_exists%` | Whether kit N exists | `Yes` / `No` |
| `%tkits_kit_<N>_is_global%` | Whether kit N is global | `Yes` / `No` |

---

## ⚠️ Known Issues & Technical Debt

### Critical / High Priority

| # | Issue | File(s) | Impact |
|---|-------|---------|--------|
| 1 | ~~**`Sound.valueOf()` is deprecated and marked for removal**~~ | `MessageUtil.java:136` | ✅ **Fixed** - Migrated to `Registry.SOUNDS.get(...)` |
| 2 | ~~**MySQL connector artifact relocated**~~ | `pom.xml:103-107` | ✅ **Fixed** - Updated to `com.mysql:mysql-connector-j` |
| 3 | ~~**`ChatColor.translateAlternateColorCodes` is deprecated**~~ | `MessageUtil.java:67` | ✅ **Fixed** - Removed redundant calls |
| 4 | **External combat plugin hooks unimplemented** | `CombatTagManager.java` | Placeholder code exists for CombatLogX/CombatTagPlus integration but is empty. Players using external combat plugins get no tag enforcement |
| 5 | ~~**`onDisable` blocking save has hard 10s timeout**~~ | `PlayerDataManager.java` | ✅ **Fixed** - Increased timeout to 30s |

### Medium Priority

| # | Issue | File(s) | Impact |
|---|-------|---------|--------|
| 6 | ~~**`KitContents.serialize()` throws `IllegalStateException`**~~ | `KitContents.java:68` | ✅ **Fixed** - Now skips problematic items and saves the rest |
| 7 | ~~**`isSameItemTypeAndMeta` uses `isSimilar` which checks amount**~~ | `UtilityService.java:216` | ✅ **Fixed** - Uses custom type/meta equality check |
| 8 | ~~**`ItemBuilder.skullOwner()` method is empty**~~ | `ItemBuilder.java:137-146` | ✅ **Fixed** - Properly queries `OfflinePlayer` |
| 9 | **YAML storage uses single-threaded executor** | `YamlStorageHandler.java:34` | All reads/writes for all players serialize through one thread. Fine for small servers, but a bottleneck at 50+ concurrent players |
| 10 | ~~**No input validation on kit numbers from commands**~~ | `DirectKitLoadCommands.java` | ✅ **Fixed** - Added bounds check based on `maxKitsPerPlayer` |

### Low Priority / Polish

| # | Issue | File(s) | Impact |
|---|-------|---------|--------|
| 11 | ~~**Kitroom items stored as Bukkit `ItemStack` serialization**~~ | `KitroomManager.java` | ✅ **Fixed** - Switched to robust Base64 serialization |
| 12 | **Magic numbers in GUI slot mapping** | `KitManager.java`, `GuiManager.java` | Armor slot offsets (36–40) are hardcoded. Consider named constants |
| 13 | **GUI title matching is fragile** | `GuiManager.java:1308-1354` | `identifyGuiFromHolder` matches GUIs by title string comparison. Any title format change breaks identification |
| 14 | ~~**No rate limiting on share code generation**~~ | `ShareCodeManager.java` | ✅ **Fixed** - Added Guava Cache rate limiter (3 seconds) |
| 15 | ~~**`PlayerData.getKits()` creates a copy on every call**~~ | `PlayerData.java:63` | ✅ **Fixed** - Uses `Collections.unmodifiableMap` to avoid copies |

---

## 🚀 Performance Improvement Suggestions

### 1. Use Virtual Threads (Java 21+)
Replace the `Executors.newSingleThreadExecutor()` / `newFixedThreadPool()` in storage handlers with `Executors.newVirtualThreadPerTaskExecutor()`. Since you're already on Java 21, virtual threads eliminate thread pool sizing concerns and improve throughput for I/O-bound storage operations.

### 2. Batch MySQL Operations
`migrateAllData()` fires individual `INSERT` statements for each kit. Use JDBC batch operations (`addBatch()` / `executeBatch()`) for 5–10× migration speed improvement.

### 3. Cache GUI Item Templates
`GuiManager.loadGuiItem()` reads from `gui.yml` FileConfiguration on every GUI open. Cache the built `ItemStack` templates on reload instead of rebuilding them per-open.

### 4. Lazy-Load Global Kits
`loadGlobalKits()` fetches all global kits on startup and holds them in memory. On servers with hundreds of global kits, this wastes memory. Consider pagination-aware lazy loading.

### 5. Replace `Sound.valueOf()` with Registry Lookup
```java
// Current (deprecated, will be removed)
Sound sound = Sound.valueOf(soundName.toUpperCase());

// Recommended
Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(soundName.toLowerCase()));
```

### 6. Use `--release` for Annotation Processing
Already implemented in this fix — ensures clean cross-compilation from any JDK to Java 21 bytecode.

### 7. Connection Pool Validation Query
Add a `connectionTestQuery` to the HikariCP config:
```java
config.setConnectionTestQuery("SELECT 1");
```
This prevents stale connections from being served after MySQL timeouts.

### 8. Decouple Combat Tag from Plugin
Consider extracting `CombatTagManager` to support configurable external providers (CombatLogX, CombatTagPlus) via a simple adapter interface, rather than the current hardcoded placeholder approach.

---

## 🔧 Building from Source

```bash
# Requirements: JDK 21+, Maven 3.8+
git clone https://github.com/takedaa83/T-Kits.git
cd T-Kits
mvn clean package
# Output: target/T-Kits-1.0.6.jar
```

### Dependencies (Shaded)

| Dependency | Version | Scope |
|-----------|---------|-------|
| [ACF (Aikar Command Framework)](https://github.com/aikar/commands) | 0.5.1-SNAPSHOT | Shaded → `com.takeda.tkits.libs.acf` |
| [AnvilGUI](https://github.com/WesJD/AnvilGUI) | 1.10.12-SNAPSHOT | Shaded → `com.takeda.tkits.libs.anvilgui` |
| [Commons Lang3](https://commons.apache.org/proper/commons-lang/) | 3.14.0 | Shaded → `com.takeda.tkits.libs.commonslang3` |

### Dependencies (Provided by Server)

| Dependency | Version | Notes |
|-----------|---------|-------|
| Paper API | 1.21.x | Server-provided |
| Lombok | 1.18.46 | Compile-time only |
| PlaceholderAPI | 2.11.5 | Optional soft-dependency |
| HikariCP | 6.3.0 | Required only if using MySQL |
| MySQL Connector/J | 8.0.33 | Required only if using MySQL |

---

## 📄 License

All rights reserved. © Takeda_Dev

---

<p align="center">
  <sub>Built with ❤️ for competitive Minecraft PvP servers</sub>
</p>
