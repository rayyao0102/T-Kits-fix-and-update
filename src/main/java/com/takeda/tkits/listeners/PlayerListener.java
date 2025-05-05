package com.takeda.tkits.listeners;

import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.GuiManager; // Added
import com.takeda.tkits.managers.PlayerDataManager;
import com.takeda.tkits.models.Kit; // Added
import java.util.Optional; // Added
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class PlayerListener implements Listener {

    private final TKits plugin;
    private final PlayerDataManager playerDataManager;
    private final GuiManager guiManager; // Added

    public PlayerListener(TKits plugin) {
        this.plugin = plugin;
        this.playerDataManager = plugin.getPlayerDataManager();
        this.guiManager = plugin.getGuiManager(); // Added
    }

    @EventHandler(priority = EventPriority.LOW) // Load data early
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        playerDataManager.loadPlayerData(uuid)
            .exceptionally(ex -> {
                 plugin.getMessageUtil().logException("CRITICAL error loading data for player " + player.getName() + " (" + uuid + ") on join!", ex);
                 plugin.getServer().getScheduler().runTask(plugin, () -> {
                     // Check if player is still online before kicking
                     if (player.isOnline()) {
                         player.kick(plugin.getMessageUtil().deserialize("&cError loading your T-Kits data. Please report this and reconnect."));
                     }
                 });
                return null;
            });
    }

    @EventHandler(priority = EventPriority.MONITOR) // Monitor to run last
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // --- NEW: Cancel pending kit choice on quit ---
        plugin.getMessageUtil().debug("[Quit] Cancelling any pending kit choice for " + player.getName());
        guiManager.cancelPendingKitChoice(uuid, Optional.empty());
        // --- End New ---

        playerDataManager.unloadPlayerData(uuid, false); // False = Don't trigger save here, rely on shutdown/periodic
    }
}