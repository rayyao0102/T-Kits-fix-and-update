package com.takeda.tkits.managers;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.takeda.tkits.TKits;
import com.takeda.tkits.util.MessageUtil;

import lombok.Getter;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;

public class CombatTagManager implements Listener {

    private final TKits plugin;
    private final MessageUtil msg;
    private boolean combatTagEnabled;
    private boolean combatTagPreventEnderpearl;
    private long combatTagDurationMillis;

    private final Cache<UUID, Long> combatTaggedPlayers;
    @Getter private final boolean internalCombatTagEnabled; 

     
     /*
     private final boolean useExternalPlugin;
     private final String externalPluginName;
     
     
     */

    public CombatTagManager(TKits plugin) {
        this.plugin = plugin;
        this.msg = plugin.getMessageUtil();
        this.combatTaggedPlayers = CacheBuilder.newBuilder()
                
                .expireAfterWrite(plugin.getConfigManager().getMainConfig().getLong("combat_tag.duration_seconds", 10) + 5, TimeUnit.SECONDS)
                .build();

         reloadConfigSettings(); 

         
         
         
         

         
         this.internalCombatTagEnabled = this.combatTagEnabled; 

         if (internalCombatTagEnabled) {
             plugin.getMessageUtil().logInfo("Internal combat tag system enabled (Duration: " + TimeUnit.MILLISECONDS.toSeconds(combatTagDurationMillis) + "s).");
             
         } else if (combatTagEnabled) {
             
             
              plugin.getMessageUtil().logWarning("External combat tag hooks are placeholder logic. Combat Tag checking may not function.");
         } else {
             plugin.getMessageUtil().logInfo("Combat Tag integration is disabled.");
         }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEnderPearlTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (event.getCause() != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;

        Player player = event.getPlayer();
        if (!combatTagEnabled || !combatTagPreventEnderpearl) return;
        if (!isTagged(player)) return;
        if (player.hasPermission("tkits.combattag.bypass")) return;

        long remainingMillis = getRemainingTagTimeMillis(player);
        double remainingSeconds = Math.ceil(remainingMillis / 1000.0);

        plugin.getMessageUtil().sendMessage(player, "in_combat", "time", String.format("%.1f", remainingSeconds));
        plugin.getMessageUtil().playSound(player, "error");
        event.setCancelled(true);
    }

     public void reloadConfigSettings() {
         this.combatTagEnabled = plugin.getConfigManager().getMainConfig().getBoolean("combat_tag.enabled", true);
         this.combatTagPreventEnderpearl = plugin.getConfigManager().getMainConfig().getBoolean("combat_tag.prevent_enderpearl", false);
         long durationSeconds = plugin.getConfigManager().getMainConfig().getLong("combat_tag.duration_seconds", 10);
         this.combatTagDurationMillis = TimeUnit.SECONDS.toMillis(durationSeconds);

         
         
          plugin.getMessageUtil().logInfo("Combat tag settings reloaded: Enabled=" + combatTagEnabled + ", PreventEnderpearl=" + combatTagPreventEnderpearl + ", Duration=" + durationSeconds + "s.");
     }

     /* Placeholder for external hook setup
     private void setupExternalHook() {
          if (!useExternalPlugin) return;
          
     }
     */


    public boolean isTagged(Player player) {
        if (!combatTagEnabled) return false;

         

        
         if (internalCombatTagEnabled) {
            Long expiryTime = combatTaggedPlayers.getIfPresent(player.getUniqueId());
            return expiryTime != null && System.currentTimeMillis() < expiryTime;
         }
         
        return false;
    }

    public long getRemainingTagTimeMillis(Player player) {
         if (!isTagged(player)) return 0; 

         

         
         if (internalCombatTagEnabled) {
            Long expiryTime = combatTaggedPlayers.getIfPresent(player.getUniqueId());
            if (expiryTime != null) {
                long remaining = expiryTime - System.currentTimeMillis();
                return Math.max(0, remaining);
            }
         }
        return 0;
    }


     
     

     @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
     public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
         Entity victimEntity = event.getEntity();
         Entity damagerEntity = event.getDamager();
         Player victim = (victimEntity instanceof Player) ? (Player) victimEntity : null;
         final Player[] attacker = {null};

         if (damagerEntity instanceof Player) {
             attacker[0] = (Player) damagerEntity;
         } else if (damagerEntity instanceof Projectile) {
             Entity shooter = (Entity) ((Projectile) damagerEntity).getShooter();
             if (shooter instanceof Player) {
                 attacker[0] = (Player) shooter;
             }
         }

         
         if (victim != null && attacker[0] != null && !victim.equals(attacker[0])) {
             tagPlayer(victim);
             tagPlayer(attacker[0]);
             plugin.getLogger().finest(() -> "Combat tagged: " + victim.getName() + " and " + attacker[0].getName());
         }
     }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
         
         combatTaggedPlayers.invalidate(event.getPlayer().getUniqueId());
    }

    private void tagPlayer(Player player) {
         long expiryTime = System.currentTimeMillis() + combatTagDurationMillis;
         combatTaggedPlayers.put(player.getUniqueId(), expiryTime);
         
         
    }

     
      @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
      public void onCommandPreProcess(PlayerCommandPreprocessEvent event) {
          if (!isTagged(event.getPlayer())) return; 

           String rawCommand = event.getMessage().split(" ")[0];
           String command = rawCommand.startsWith("/") ? rawCommand : "/" + rawCommand;
           command = command.toLowerCase(); 

           
           List<String> blockedCommandsList = plugin.getConfigManager().getCombatTagBlockedCommands();
           
           Set<String> blockedCommands = new HashSet<>(blockedCommandsList); 
           
           
           
           
           
           if (blockedCommands.contains(command)) {
               Player player = event.getPlayer();
               
                if(!player.hasPermission("tkits.combattag.bypass")) { 
                    long remainingMillis = getRemainingTagTimeMillis(player);
                    double remainingSeconds = Math.ceil(remainingMillis / 1000.0);

                    plugin.getMessageUtil().sendMessage(player, "in_combat", "time", String.format("%.1f", remainingSeconds));
                    plugin.getMessageUtil().playSound(player, "error");
                    event.setCancelled(true);
                } else {
                    msg.debug("Player " + player.getName() + " bypassed combat tag command block for " + command);
                }
           }
      }
}
