# T-Kits v1.1 — Release Notes

**Release Date:** May 3, 2026  
**Minecraft:** Paper 1.21.x  
**Java:** 21+

---

## 🚀 What's New

### Performance

- **Virtual Threads** — Storage handlers now use Java 21 virtual thread executors (`Executors.newThreadPerTaskExecutor`) instead of fixed/single-thread pools, eliminating thread pool sizing concerns and improving I/O throughput under load
- **JDBC Batch Operations** — MySQL migrations and bulk kit saves use `addBatch()` / `executeBatch()` for up to 10× throughput improvement
- **GUI Item Caching** — Static GUI items (backgrounds, fillers, navigation buttons) are now cached on config reload and cloned at render time, avoiding repeated YAML parsing and `ItemStack` construction on every GUI open
- **HikariCP Connection Validation** — Added `SELECT 1` connection test query to prevent stale MySQL connections after server-side timeouts

### Code Quality & Reliability

- **Eliminated All Magic Numbers** — Armor/offhand slot indices (`36–40`) replaced with named constants (`KitManager.SLOT_BOOTS`, `SLOT_LEGGINGS`, `SLOT_CHESTPLATE`, `SLOT_HELMET`, `SLOT_OFFHAND`, `MAX_EDITOR_SLOT`)
- **Robust GUI Identification** — `identifyGuiFromHolder()` no longer relies on fragile title string matching. Now uses the viewer's `GuiState` history stack for 100% reliable identification regardless of locale or title format changes
- **Combat Tag Cleanup** — Removed dead placeholder code for external combat plugin hooks. Blocked commands set is now pre-computed on config reload (O(1) lookup) instead of creating a new `HashSet` per command event. Updated to modern Java pattern matching for `instanceof` checks
- **Resilient Serialization** — `KitContents.serialize()` tests each item individually, gracefully skipping corrupted entries instead of failing the entire kit save
- **Rate-Limited Sharing** — `/kit share` now has a 3-second Guava Cache rate limiter to prevent code generation spam
- **Optimized PlayerData** — `getKits()` returns `Collections.unmodifiableMap()` instead of `Map.copyOf()`, avoiding unnecessary memory allocation on each access

### Bug Fixes

- **PlaceholderAPI Changed to Soft Dependency** — Plugin no longer fails to load on servers without PlaceholderAPI installed
- **CI Pipeline Fixed** — Added `permissions: contents: write` to the GitHub Actions workflow, resolving the dependency graph submission 403 error
- **Sound API Migrated** — Replaced deprecated `Sound.valueOf()` with `Registry.SOUNDS.get(NamespacedKey)` for forward compatibility
- **Base64 Kitroom Storage** — Kitroom items now use robust Base64 serialization instead of fragile Bukkit native YAML format
- **Skull Owner Fix** — `ItemBuilder.skullOwner()` now properly queries `OfflinePlayer` instead of being a no-op
- **Kit Number Validation** — `/k1`–`/k7` commands now validate against `maxKitsPerPlayer` bounds
- **Shutdown Safety** — Blocking save timeout increased to 30 seconds to accommodate slow storage backends

### Project

- **Version bumped to 1.1**
- **Professional README** — Complete rewrite with live CI badge, clean feature table, and production-focused documentation
- **Plugin description** updated from casual to professional

---

## ⚠️ Breaking Changes

None. This is a drop-in upgrade from 1.0.x.

---

## 📦 Upgrade Guide

1. Replace your existing `T-Kits-1.0.x.jar` with `T-Kits-1.1.jar`
2. Restart the server
3. No config changes required — all improvements are backward compatible

---

## 📊 Remaining Known Limitations

| Item | Status | Notes |
|------|--------|-------|
| Lazy-load global kits | Planned | All global kits load into memory at startup; may waste RAM with 100+ global kits |
| SQLite backend | Planned | Alternative to MySQL for medium-scale servers wanting SQL without external infra |

---

<p align="center">
  <sub>Full changelog: <a href="https://github.com/takedaa83/T-Kits/compare/v1.0.6...v1.1">v1.0.6...v1.1</a></sub>
</p>
