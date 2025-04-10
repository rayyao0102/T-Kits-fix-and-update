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
    @Getter private final boolean internalCombatTagEnabled; // Flag indicating if internal listener is active

     // Example placeholder for external hook logic
     /*
     private final boolean useExternalPlugin;
     private final String externalPluginName;
     // Placeholder interface and potential implementations would go here
     // Example: private ExternalCombatTagHook externalHook;
     */

    public CombatTagManager(TKits plugin) {
        this.plugin = plugin;
        this.msg = plugin.getMessageUtil();
        this.combatTaggedPlayers = CacheBuilder.newBuilder()
                // Expire cache entries slightly longer than the tag duration for safety
                .expireAfterWrite(plugin.getConfigManager().getMainConfig().getLong("combat_tag.duration_seconds", 10) + 5, TimeUnit.SECONDS)
                .build();

         reloadConfigSettings(); // Load initial settings

         // Determine if internal system should be used
         // String externalHookName = plugin.getConfigManager().getMainConfig().getString("combat_tag.external_plugin_hook", "").trim();
         // this.useExternalPlugin = combatTagEnabled && !externalHookName.isEmpty() && Bukkit.getPluginManager().isPluginEnabled(externalHookName);
         // this.externalPluginName = externalHookName;

         // For now, only implement internal system:
         this.internalCombatTagEnabled = this.combatTagEnabled; // Internal system active if main setting is enabled

         if (internalCombatTagEnabled) {
             plugin.getMessageUtil().logInfo("Internal combat tag system enabled (Duration: " + TimeUnit.MILLISECONDS.toSeconds(combatTagDurationMillis) + "s).");
             // Listener registration is now handled in TKits main class based on this flag
         } else if (combatTagEnabled) {
             // plugin.getMessageUtil().logInfo("Combat tag enabled, attempting to use external hook: " + externalPluginName);
             // setupExternalHook(); // Setup placeholder
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

         // If using Guava cache, can't easily change expiry time after creation without rebuilding.
         // The impact is minor if duration changes - tags might expire slightly early/late until next cache expiry+rebuild.
          plugin.getMessageUtil().logInfo("Combat tag settings reloaded: Enabled=" + combatTagEnabled + ", PreventEnderpearl=" + combatTagPreventEnderpearl + ", Duration=" + durationSeconds + "s.");
     }

     /* Placeholder for external hook setup
     private void setupExternalHook() {
          if (!useExternalPlugin) return;
          // try { based on externalPluginName, instantiate specific hook class } catch NoClassDefFoundError etc {}
     }
     */


    public boolean isTagged(Player player) {
        if (!combatTagEnabled) return false;

         // if (useExternalPlugin && externalHook != null) return externalHook.isTagged(player);

        // Internal check
         if (internalCombatTagEnabled) {
            Long expiryTime = combatTaggedPlayers.getIfPresent(player.getUniqueId());
            return expiryTime != null && System.currentTimeMillis() < expiryTime;
         }
         // If external hook failed or internal is disabled but main is enabled, default to not tagged?
        return false;
    }

    public long getRemainingTagTimeMillis(Player player) {
         if (!isTagged(player)) return 0; // Use isTagged for unified check

         // if (useExternalPlugin && externalHook != null) return externalHook.getRemainingTagTimeMillis(player);

         // Internal check
         if (internalCombatTagEnabled) {
            Long expiryTime = combatTaggedPlayers.getIfPresent(player.getUniqueId());
            if (expiryTime != null) {
                long remaining = expiryTime - System.currentTimeMillis();
                return Math.max(0, remaining);
            }
         }
        return 0;
    }


     // --- Internal Tagging Listener Methods ---
     // Note: These only run if internalCombatTagEnabled=true and listener registered in main class

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

         // Tag if a player attacked a player
         if (victim != null && attacker[0] != null && !victim.equals(attacker[0])) {
             tagPlayer(victim);
             tagPlayer(attacker[0]);
             plugin.getLogger().finest(() -> "Combat tagged: " + victim.getName() + " and " + attacker[0].getName());
         }
     }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
         // Internal system: Clear tag on logout to prevent issues? Or let it expire? Clear is simpler.
         combatTaggedPlayers.invalidate(event.getPlayer().getUniqueId());
    }

    private void tagPlayer(Player player) {
         long expiryTime = System.currentTimeMillis() + combatTagDurationMillis;
         combatTaggedPlayers.put(player.getUniqueId(), expiryTime);
         // Optionally send action bar message? Can be spammy.
         // plugin.getMessageUtil().sendActionBar(player, "you_are_in_combat"); // Add message if desired
    }

     // Command Blocking (for specified commands if tagged)
      @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
      public void onCommandPreProcess(PlayerCommandPreprocessEvent event) {
          if (!isTagged(event.getPlayer())) return; // Use the unified isTagged check

           String rawCommand = event.getMessage().split(" ")[0];
           String command = rawCommand.startsWith("/") ? rawCommand : "/" + rawCommand;
           command = command.toLowerCase(); // Ensure lowercase for matching

           // Load blocked commands from config dynamically
           List<String> blockedCommandsList = plugin.getConfigManager().getCombatTagBlockedCommands();
           // Convert to Set for efficient checking
           Set<String> blockedCommands = new HashSet<>(blockedCommandsList); 
           // Ensure they start with / and are lowercase (ConfigManager already handles lowercase)
           // Add aliases programmatically if needed, or ensure aliases are in config
           // Example: if (blockedCommands.contains("/regear")) blockedCommands.add("/rg");
           // ConfigManager should load these correctly, including the leading /
           
           if (blockedCommands.contains(command)) {
               Player player = event.getPlayer();
               // Check bypass permission before blocking
                if(!player.hasPermission("tkits.combattag.bypass")) { // Use a specific bypass permission
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
