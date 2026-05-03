package com.takeda.tkits.storage;

import com.takeda.tkits.models.Kit;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface StorageHandler {

    void init() throws Exception;
    void shutdown();

    
    CompletableFuture<Map<Integer, Kit>> loadPlayerKits(UUID playerUUID);
    CompletableFuture<Void> savePlayerKit(UUID playerUUID, Kit kit);
    default CompletableFuture<Void> saveKits(List<Kit> kits) {
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (Kit kit : kits) futures.add(savePlayerKit(kit.getOwner(), kit));
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    CompletableFuture<Void> deletePlayerKit(UUID playerUUID, int kitNumber);

    
    CompletableFuture<List<Kit>> loadGlobalKits(); 
    CompletableFuture<Void> saveGlobalKit(Kit kit); 
    CompletableFuture<Void> deleteGlobalKit(UUID ownerUUID, int kitNumber); 

    
    CompletableFuture<Void> migratePlayerData(UUID playerUUID, StorageHandler targetHandler);
    CompletableFuture<Void> migrateAllData(StorageHandler targetHandler);
}