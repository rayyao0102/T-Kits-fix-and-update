package com.takeda.tkits.listeners;

import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.GuiManager; 
import com.takeda.tkits.managers.PlayerDataManager;
import com.takeda.tkits.models.Kit; 
import java.util.Optional; 
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
    private final GuiManager guiManager; 

    public PlayerListener(TKits plugin) {
        this.plugin = plugin;
        this.playerDataManager = plugin.getPlayerDataManager();
        this.guiManager = plugin.getGuiManager(); 
    }

    @EventHandler(priority = EventPriority.LOW) 
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        playerDataManager.loadPlayerData(uuid)
            .exceptionally(ex -> {
                 plugin.getMessageUtil().logException("CRITICAL error loading data for player " + player.getName() + " (" + uuid + ") on join!", ex);
                 plugin.getServer().getScheduler().runTask(plugin, () -> {
                     
                     if (player.isOnline()) {
                         player.kick(plugin.getMessageUtil().deserialize("&cError loading your T-Kits data. Please report this and reconnect."));
                     }
                 });
                return null;
            });
    }

    @EventHandler(priority = EventPriority.MONITOR) 
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        
        plugin.getMessageUtil().debug("[Quit] Cancelling any pending kit choice for " + player.getName());
        guiManager.cancelPendingKitChoice(uuid, Optional.empty());
        

        playerDataManager.unloadPlayerData(uuid, false); 
    }
}