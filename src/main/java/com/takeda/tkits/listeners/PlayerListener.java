package com.takeda.tkits.listeners;

import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.PlayerDataManager;
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

    public PlayerListener(TKits plugin) {
        this.plugin = plugin;
        this.playerDataManager = plugin.getPlayerDataManager();
    }

    @EventHandler(priority = EventPriority.LOW) // Load data early
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Load data async, handle potential failures during load
        playerDataManager.loadPlayerData(uuid)
            .exceptionally(ex -> {
                 // Log detailed error
                 plugin.getMessageUtil().logException("CRITICAL error loading data for player " + player.getName() + " (" + uuid + ") on join!", ex);
                 // Kick player to prevent data corruption/issues? Ensure message uses Component.
                 plugin.getServer().getScheduler().runTask(plugin, () -> // Schedule kick for main thread
                      player.kick(plugin.getMessageUtil().deserialize("&cError loading your T-Kits data. Please report this and reconnect."))
                 );
                return null; // Complete exceptionally, PlayerData might be null/empty downstream initially
            });
    }

    @EventHandler(priority = EventPriority.MONITOR) // Monitor to run last
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // Unload data, potentially saving if configured (default is save on shutdown)
        playerDataManager.unloadPlayerData(uuid, false); // False = Don't trigger save here, rely on shutdown/periodic
    }
}