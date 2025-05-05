# ✨ T-Kits - The Ultimate Minecraft Kit Plugin Suite for 1.21+ ✨

Welcome to **T-Kits**! The all-in-one, modern, and powerful kit management plugin for Minecraft servers. Whether you're running a competitive PvP network or a cozy SMP, T-Kits brings you next-level features, beautiful GUIs, and a developer-friendly API—all with blazing performance and reliability.

---

## 🚀 Features
- **Unlimited Kits** (configurable per player)
- **Animated, Themeable GUIs** for kit selection, editing, previewing, and admin control
- **Kit Sharing**: Share/import kits with unique codes, global kit browsing
- **Ender Chest Integration**: Link Ender Chest contents to kits
- **Kitroom**: Server-wide item repository with categories, cooldowns, and permissions
- **Combat Tag System**: Prevents kit use during combat, blocks commands, and restricts actions
- **Regear & Arrange**: Instantly regear or arrange inventory to last kit
- **Powerful Admin Tools**: Kitroom management, migration, reload, cooldown reset, and more
- **YAML & MySQL Storage**: Hot-reloadable, with seamless migration tools
- **PlaceholderAPI Support**: Built-in expansion for kit and combat placeholders
- **Extensible API**: For custom plugins, events, and storage backends
- **Localization**: All messages and sounds are fully customizable

---

## 🛠️ Installation
1. **Download** the latest T-Kits JAR from releases.
2. **Upload** `T-Kits.jar` to your server's `/plugins` directory.
3. **Restart** your server to generate config files.
4. **Enjoy!** Use `/kit` or `/tkits help` in-game.

---

## 🧑‍💻 Commands & Permissions
### Player Commands
- `/kit` — Open main kit GUI (`tkits.use`)
- `/k1`–`/k7` — Load kits 1–7 (`tkits.load.1` ... `tkits.load.7`)
- `/regear` or `/rg` — Regear inventory (`tkits.regear`)
- `/arrange` or `/ag` — Arrange inventory (`tkits.arrange`)
- `/kit import <code>` — Import a kit by share code (`tkits.kit.share`)
- `/kitroom` — Open Kitroom GUI (`tkits.kitroom.use`)

### Admin Commands
- `/tkitsadmin` or `/tka` — Root admin command (`tkits.admin`)
- `/tkitsadmin kitroom save` — Save Kitroom to `kitroom.yml`
- `/tkitsadmin kitroom reload` — Reload Kitroom from file
- `/tkitsadmin kitroom resetdefaults` — Reset Kitroom categories/items
- `/tkitsadmin reload` — Reload all config files
- `/tkitsadmin migrate <YAML|MYSQL> [--all|<Player/UUID>]` — Migrate storage
- `/tkitsadmin kit clear <player> <kit_number>` — Clear a player's kit
- `/tkitsadmin kit preview <player> <kit_number>` — Preview a player's kit
- `/tkitsadmin give regearitem <player>` — Give a Regear Box
- `/tkitsadmin cooldown reset <player> <kit_load|regear|arrange|all>` — Reset cooldowns

### Permissions Overview
- `tkits.use` — Use main /kit command and GUI
- `tkits.load.1` ... `tkits.load.7` — Load specific kits
- `tkits.regear` — Use /regear
- `tkits.arrange` — Use /arrange
- `tkits.kit.share` — Share/import kits
- `tkits.kitroom.use` — Use Kitroom
- `tkits.kitroom.admin` — Modify Kitroom
- `tkits.admin` — All admin commands
- `tkits.cooldown.bypass` — Bypass cooldowns
- `tkits.combattag.bypass` — Bypass combat tag

---

## ⚙️ Configuration
- **config.yml** — Main settings (debug, storage, kit limits, cooldowns, combat tag, etc.)
- **gui.yml** — GUI layouts, item definitions, titles, and themes
- **kitroom.yml** — Kitroom categories and item mappings
- All config files are hot-reloadable via `/tkitsadmin reload`

---

## 📦 Developer API
- Exposes `TKitsAPI` for kit management, regear, arrange, combat tag, and more
- PlaceholderAPI expansion for kit and combat placeholders
- Extensible: add new storage backends, event hooks, and custom GUIs

**Example Usage:**
```java
RegisteredServiceProvider<TKitsAPI> provider = Bukkit.getServicesManager().getRegistration(TKitsAPI.class);
if (provider != null) {
    TKitsAPI api = provider.getProvider();
    api.loadKitOntoPlayer(player, 1);
}
```

---

## 💬 Support & Community
- [💬 Discord](#) — Community & staff support
- [📚 Documentation](#) — Guides, FAQs, and API docs
- [🐛 GitHub Issues](#) — Bug reports & feature requests

---

## 📝 License
T-Kits is © Takeda. Not affiliated with Mojang Studios or Microsoft. See LICENSE for details.

---

> Made with ❤️ for Minecraft server owners. Happy kitting!
