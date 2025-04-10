package com.takeda.tkits.managers;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.KitContents;
import com.takeda.tkits.models.PlayerData;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import org.apache.commons.lang3.RandomStringUtils;


import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ShareCodeManager {

    private final TKits plugin;
    private Cache<String, ShareData> shareCodeCache; // Code -> ShareData

    private int codeLength;
    private boolean codeOneTimeUse;
    private long codeExpirationMinutes;

    public ShareCodeManager(TKits plugin) {
        this.plugin = plugin;
        loadConfigSettings(); // Load initial settings
        buildCache(); // Build cache based on settings
    }

    private void loadConfigSettings() {
         codeLength = plugin.getConfigManager().getMainConfig().getInt("sharing.code_length", 5);
         codeOneTimeUse = plugin.getConfigManager().getMainConfig().getBoolean("sharing.code_one_time_use", true);
         codeExpirationMinutes = plugin.getConfigManager().getMainConfig().getLong("sharing.code_expiration_minutes", 5);
          plugin.getLogger().fine("Share code settings loaded: Length=" + codeLength + ", OneTime=" + codeOneTimeUse + ", Expires=" + codeExpirationMinutes + "min");
    }

    // Allows reloading settings without recreating the manager
    public void reloadConfigSettings() {
        loadConfigSettings();
        // Rebuild cache with new expiration time
        buildCache();
         plugin.getMessageUtil().logInfo("Share code settings reloaded.");
    }

     private void buildCache() {
        // Invalidate old cache before creating new one if settings change significantly
        if (this.shareCodeCache != null) {
            this.shareCodeCache.invalidateAll();
        }
        this.shareCodeCache = CacheBuilder.newBuilder()
                .expireAfterWrite(codeExpirationMinutes, TimeUnit.MINUTES)
                .maximumSize(10000) // Prevent excessive memory usage
                .build();
        plugin.getLogger().fine("Share code cache rebuilt with expiry of " + codeExpirationMinutes + " minutes.");
     }

    /**
     * Generates a share code for a given kit number belonging to the player.
     * Reads the kit data from PlayerDataManager.
     * @param player The player generating the code.
     * @param kitNumber The kit number to share.
     * @return The generated share code, or null on failure.
     */
    public String generateShareCode(Player player, int kitNumber) {
        PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if (playerData == null) {
             plugin.getMessageUtil().logWarning("Attempted to generate share code for player " + player.getName() + " but PlayerData not found.");
            return null;
        }

        Kit kit = playerData.getKit(kitNumber);
        if (kit == null) {
             plugin.getMessageUtil().sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNumber));
             plugin.getMessageUtil().playSound(player, "error");
            return null;
        }
        // Call the new method which handles the actual logic
        return generateShareCodeFromKit(player.getUniqueId(), kit);
    }

    /**
     * Generates a share code for a specific Kit object provided.
     * Use this when you have the Kit object already (e.g., from GUI editor).
     * @param ownerUUID The UUID of the player generating the code.
     * @param kit The Kit object to share (should be a snapshot/copy).
     * @return The generated share code, or null on failure.
     */
    public String generateShareCodeFromKit(UUID ownerUUID, Kit kit) {
        if (kit == null) return null;

        // --- Config Check ---
        boolean allowGlobalShare = plugin.getConfigManager().isAllowSharingGlobalKits();
        if (kit.isGlobal() && !allowGlobalShare) {
            plugin.getMessageUtil().sendMessage(Bukkit.getPlayer(ownerUUID), "cannot_share_global_kit"); // Add message
            plugin.getMessageUtil().playSound(Bukkit.getPlayer(ownerUUID), "error");
            return null;
        }
        // --- End Config Check ---

        String code;
        int retries = 0;
        final int maxRetries = 10; // Safety limit

        do {
            // Generate using only letters and numbers for easier typing
            code = RandomStringUtils.randomAlphanumeric(codeLength).toUpperCase();
            if (retries++ > maxRetries) {
                plugin.getMessageUtil().logSevere("Failed to generate a unique share code after " + maxRetries + " attempts for kit " + kit.getKitNumber() + " from " + ownerUUID);
                // Don't send message here as it might be called async without player context
                return null;
            }
        } while (shareCodeCache.getIfPresent(code) != null);

        // Create a deep copy of the kit AGAIN before storing in cache, just to be absolutely sure.
        Kit sharedKitCopy = kit.toBuilder()
            .contents(KitContents.deserialize(KitContents.serialize(kit.getContents())))
            .enderChestContents(KitContents.deserialize(KitContents.serialize(kit.getEnderChestContents())))
            // Ensure owner/kitNumber are correct in the copy
            .owner(ownerUUID)
            .kitNumber(kit.getKitNumber())
            .build();

        ShareData data = new ShareData(ownerUUID, sharedKitCopy, codeOneTimeUse);
        shareCodeCache.put(code, data);
        plugin.getLogger().info("Generated share code " + code + " for kit " + kit.getKitNumber() + " from " + ownerUUID + ".");

        return code;
    }


    /**
     * Redeems a share code to get the associated Kit data.
     * If the code is one-time use, it is invalidated upon successful redemption.
     * @param code The share code (case-insensitive).
     * @return The shared Kit if the code is valid and not expired, otherwise null.
     */
    public Kit redeemShareCode(String code) {
        if (code == null) return null;
        String upperCode = code.toUpperCase(); // Normalize code
        ShareData data = shareCodeCache.getIfPresent(upperCode);

        if (data == null) {
            plugin.getLogger().fine("Attempted to redeem invalid/expired share code: " + upperCode);
            return null; // Code expired or never existed
        }

        // Return a copy of the stored kit data to prevent external modification of cache content
         Kit kitToReturn = data.getSharedKit().toBuilder()
             // Deep copy contents again just in case? Probably overkill if initial copy was good.
             .contents(KitContents.deserialize(KitContents.serialize(data.getSharedKit().getContents())))
             .enderChestContents(KitContents.deserialize(KitContents.serialize(data.getSharedKit().getEnderChestContents())))
             .build();


        if (data.isOneTimeUse()) {
             shareCodeCache.invalidate(upperCode); // Remove immediately after retrieval
              plugin.getLogger().info("Redeemed and invalidated one-time use share code: " + upperCode);
        } else {
              plugin.getLogger().info("Redeemed multi-use share code: " + upperCode);
        }

        return kitToReturn;
    }

    // --- Private ShareData Class ---
    @Getter
    @AllArgsConstructor
    private static class ShareData {
        private final UUID originalOwner;
        private final Kit sharedKit; // Immutable copy stored here
        private final boolean oneTimeUse;
    }
}