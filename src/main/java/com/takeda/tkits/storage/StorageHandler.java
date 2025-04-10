package com.takeda.tkits.storage;

import com.takeda.tkits.models.Kit;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface StorageHandler {

    void init() throws Exception;
    void shutdown();

    // Player Kits
    CompletableFuture<Map<Integer, Kit>> loadPlayerKits(UUID playerUUID);
    CompletableFuture<Void> savePlayerKit(UUID playerUUID, Kit kit);
    CompletableFuture<Void> deletePlayerKit(UUID playerUUID, int kitNumber);

    // Global Kits (often derived from player kits where kit.isGlobal() is true)
    CompletableFuture<List<Kit>> loadGlobalKits(); // Load all kits marked as global
    CompletableFuture<Void> saveGlobalKit(Kit kit); // Ensures kit is marked global before saving
    CompletableFuture<Void> deleteGlobalKit(UUID ownerUUID, int kitNumber); // Mark kit as not global

    // Migration
    CompletableFuture<Void> migratePlayerData(UUID playerUUID, StorageHandler targetHandler);
    CompletableFuture<Void> migrateAllData(StorageHandler targetHandler);
}