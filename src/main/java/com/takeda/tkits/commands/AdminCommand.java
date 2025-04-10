package com.takeda.tkits.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import co.aikar.commands.bukkit.contexts.OnlinePlayer; // ACF Context
import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.GuiManager;
import com.takeda.tkits.managers.KitManager;
import com.takeda.tkits.services.CooldownService;
import com.takeda.tkits.storage.MySQLStorageHandler;
import com.takeda.tkits.storage.StorageHandler;
import com.takeda.tkits.storage.YamlStorageHandler;
import com.takeda.tkits.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material; // Needed for give regear item check
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack; // Needed for give regear item

import java.util.Map; // Needed for give regear item
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


@CommandAlias("tkitsadmin|tka")
@Description("T-Kits Administrative Commands")
@CommandPermission("tkits.admin") // Root permission for all admin subcommands
public class AdminCommand extends BaseCommand {

    private final TKits plugin;
    private final MessageUtil msg;
    private final GuiManager guiManager;
    private final KitManager kitManager;

    public AdminCommand(TKits plugin) {
        this.plugin = plugin;
        this.msg = plugin.getMessageUtil();
        this.guiManager = plugin.getGuiManager();
        this.kitManager = plugin.getKitManager();
    }

    // Removed addpage/removepage subcommands

    @Subcommand("kitroom save")
    @Description("Save Kitroom categories to kitroom.yml")
    @CommandPermission("tkits.admin")
    public void onSaveKitroom(CommandSender sender) {
        plugin.getKitroomManager().saveKitroom();
        msg.sendRawMessage(sender, "&aKitroom saved.", null);
    }

    @Subcommand("kitroom reload")
    @Description("Reload Kitroom from kitroom.yml")
    @CommandPermission("tkits.admin")
    public void onReloadKitroom(CommandSender sender) {
        plugin.getKitroomManager().loadKitroom();
        msg.sendRawMessage(sender, "&aKitroom reloaded.", null);
    }

    @Subcommand("kitroom resetdefaults")
    @Description("Generate default Kitroom categories/items")
    @CommandPermission("tkits.admin")
    public void onResetDefaults(CommandSender sender) {
        plugin.getKitroomManager().generateDefaultKitroom();
        plugin.getKitroomManager().saveKitroom();
        msg.sendRawMessage(sender, "&aKitroom reset to defaults.", null);
    }

    @Subcommand("reload")
    @Description("Reloads T-Kits config.yml & kitroom.yml.")
    public void onReload(CommandSender sender) {
         plugin.reloadPlugin();
         msg.sendMessage(sender, "plugin_reloaded");
    }

    @Subcommand("migrate")
    @Syntax("<target_type> [--all | <Player/UUID>]")
    @CommandCompletion("@storage_types @players|--all") // Use defined completions
    @Description("Migrates kit data to YAML or MYSQL storage.")
    public void onMigrate(CommandSender sender, String targetTypeStr, @Optional String targetPlayer) {

        StorageHandler currentHandler = plugin.getStorageHandler();
        StorageHandler targetHandler = null;
        String targetType = targetTypeStr.toUpperCase();
        String fromType = (currentHandler instanceof YamlStorageHandler) ? "YAML" : "MySQL";

        if (fromType.equals(targetType)) {
             // Send using MessageUtil formatting for consistency
             msg.sendRawMessage(sender, "&eAlready using " + fromType + " storage.", null);
            return;
        }

        try {
            if (targetType.equals("YAML")) {
                targetHandler = new YamlStorageHandler(plugin);
                targetHandler.init();
            } else if (targetType.equals("MYSQL")) {
                targetHandler = new MySQLStorageHandler(plugin);
                targetHandler.init();
            } else {
                 // Use sendRawMessage for direct feedback without prefix
                 msg.sendRawMessage(sender, "&cInvalid target storage type. Use YAML or MYSQL.", null);
                 return;
            }
        } catch (Exception e) {
             msg.sendRawMessage(sender, "&cFailed to initialize target storage (" + targetType + "): " + e.getMessage(), null);
             msg.logException("Migration prerequisite failed: Could not init target handler.", e);
             if (targetHandler != currentHandler && targetHandler instanceof MySQLStorageHandler) { targetHandler.shutdown(); }
             return;
         }

        msg.sendMessage(sender, "migration_started", "from", fromType, "to", targetType);
         // Send detailed console message using raw method
         msg.sendRawMessage(sender, "&eAttempting migration... See console for details.", null);

        CompletableFuture<Void> migrationFuture;
        boolean migrateAll = targetPlayer == null || targetPlayer.equalsIgnoreCase("--all");

        if (!migrateAll) {
            UUID playerUUID = null;
            String targetName = targetPlayer; // Store original input for logging
            try {
                playerUUID = UUID.fromString(targetPlayer);
            } catch (IllegalArgumentException e) {
                 Player p = Bukkit.getPlayerExact(targetPlayer); // Use getPlayerExact for case-sensitivity if desired
                 if (p != null) {
                     playerUUID = p.getUniqueId();
                     targetName = p.getName(); // Use correct name for logging
                 } else {
                     msg.sendRawMessage(sender, "&cInvalid player name or UUID specified: " + targetPlayer, null);
                     if (targetHandler != currentHandler) targetHandler.shutdown();
                     return;
    }
            } 
             msg.sendRawMessage(sender, "&eMigrating data for: " + targetName + " (" + playerUUID + ")", null);
             migrationFuture = currentHandler.migratePlayerData(playerUUID, targetHandler);

        } else {
             msg.sendRawMessage(sender, "&eMigrating ALL data... This may take time. Check console progress.", null);
             migrationFuture = currentHandler.migrateAllData(targetHandler);
        }

        final StorageHandler finalTargetHandler = targetHandler;
        migrationFuture.whenCompleteAsync((result, error) -> {
             if (finalTargetHandler != currentHandler) {
                 finalTargetHandler.shutdown();
             }

             if (error != null) {
                  msg.sendMessage(sender, "migration_failed");
                  msg.logException("Data migration failed!", error);
             } else {
                  msg.sendMessage(sender, "migration_success");
                  // Use Component builder for the warning message for better formatting control
                  sender.sendMessage(
                       Component.text().color(NamedTextColor.GREEN)
                           .append(Component.text("Migration complete! "))
                           .append(Component.text("PLEASE UPDATE 'storage.type' in config.yml to '" + targetType + "' and RESTART the server!", NamedTextColor.YELLOW, TextDecoration.BOLD))
                           .build()
                  );
             }
         }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
    }


     // --- Player Kit Management Subcommands ---
    @Subcommand("kit clear")
    @Syntax("<player> <kit_number>")
    @CommandCompletion("@players @tkits_kits") // Suggest online players, then suggest kit numbers 1-7
    @Description("Forcefully clears a player's specified kit.")
    public void onKitClear(CommandSender sender, OnlinePlayer targetPlayer, @Conditions("limits:min=1,max=7") int kitNumber) {
        Player player = targetPlayer.getPlayer();
         kitManager.clearKit(player, kitNumber); // This handles messages to the target player
        // Send confirmation to the admin executing the command
         msg.sendRawMessage(sender, "&aAttempted to clear Kit " + kitNumber + " for player " + player.getName() + ".", null);
    }

     @Subcommand("kit preview")
     @Syntax("<player> <kit_number>")
     @CommandCompletion("@players @tkits_kits") // Suggest existing kit numbers for the target? More complex ACF needed. Basic 1-7.
     @Description("Opens a preview GUI of a player's kit for the command sender.")
     public void onKitPreview(Player sender, OnlinePlayer targetPlayer, @Conditions("limits:min=1,max=7") int kitNumber) {
        Player target = targetPlayer.getPlayer();
         // Use API to get kit data async
         plugin.getApi().getPlayerKitAsync(target.getUniqueId(), kitNumber).thenAcceptAsync(optionalKit -> {
            if(optionalKit.isPresent()){
                 guiManager.openKitPreview(sender, optionalKit.get()); // Open preview for the admin (sender)
            } else {
                 // Use sendMessage for prefix and formatting
                 msg.sendMessage(sender, "kit_not_found", "kit_number", String.valueOf(kitNumber));
                  msg.playSound(sender, "error");
            }
         }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin)); // Run GUI open on main thread
     }

      @Subcommand("give regearitem")
      @Syntax("<player>")
      @CommandCompletion("@players")
      @Description("Gives the specified player a Regear Box item.")
     public void onGiveRegearItem(CommandSender sender, OnlinePlayer targetPlayer) {
         Player target = targetPlayer.getPlayer();
          ItemStack regearBox = plugin.getApi().getRegearTriggerItem();

          if (regearBox != null && regearBox.getType() != Material.AIR) { // Check item validity
               Map<Integer, ItemStack> leftover = target.getInventory().addItem(regearBox);
               if(!leftover.isEmpty()){
                   msg.sendRawMessage(sender, "&c" + target.getName() + "'s inventory was full, Regear Box dropped.", null);
                   leftover.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
               } else {
                    msg.sendRawMessage(sender, "&aGave Regear Box to " + target.getName() + ".", null);
                    msg.sendMessage(target, "&aYou received a Regear Box from an admin!"); // Notify player using standard message format
               }
          } else {
               msg.sendRawMessage(sender, "&cFailed to create Regear Box item (API returned AIR).", null);
          }
     }

      @Subcommand("cooldown reset")
      @Syntax("<player> <kit_load|regear|arrange|all>")
      @CommandCompletion("@players kit_load|regear|arrange|all")
      @Description("Resets specific or all command cooldowns for a player.")
      public void onCooldownReset(CommandSender sender, OnlinePlayer targetPlayer, String cooldownType) {
           Player target = targetPlayer.getPlayer();
           CooldownService cooldownService = plugin.getCooldownService();
           String typeLower = cooldownType.toLowerCase();
           boolean reset = false;
           String resetTypeName = "N/A"; // For feedback message

           switch(typeLower) {
                case "kit_load": cooldownService.resetCooldown(target.getUniqueId(), CooldownService.CooldownType.KIT_LOAD); reset = true; resetTypeName = "Kit Load"; break;
                case "regear": cooldownService.resetCooldown(target.getUniqueId(), CooldownService.CooldownType.REGEAR); reset = true; resetTypeName = "Regear"; break;
                case "arrange": cooldownService.resetCooldown(target.getUniqueId(), CooldownService.CooldownType.ARRANGE); reset = true; resetTypeName = "Arrange"; break;
                case "all": cooldownService.resetAllCooldowns(target.getUniqueId()); reset = true; resetTypeName = "All"; break;
                default: msg.sendRawMessage(sender, "&cInvalid cooldown type. Use: kit_load, regear, arrange, or all.", null); break;
           }

           if(reset) {
                // Use raw message for admin feedback
                msg.sendRawMessage(sender, "&aReset '" + resetTypeName + "' cooldown(s) for " + target.getName() + ".", null);
                if (!sender.equals(target)) { // Notify player if not resetting own cooldown
                    msg.sendMessage(target, "&aYour '" + resetTypeName + "' T-Kits cooldown(s) have been reset by an admin.");
                }
           }
      }
    }
