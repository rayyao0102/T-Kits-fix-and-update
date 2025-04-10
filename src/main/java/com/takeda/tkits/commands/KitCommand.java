package com.takeda.tkits.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.GuiManager;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.util.MessageUtil;
import com.takeda.tkits.services.CooldownService;
import org.bukkit.entity.Player;


@CommandAlias("kit|k")
@Description("Opens the main T-Kits GUI or imports a kit")
public class KitCommand extends BaseCommand {

    private final TKits plugin;
    private final GuiManager guiManager;
    private final MessageUtil msg;

    public KitCommand(TKits plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager();
        this.msg = plugin.getMessageUtil();
    }

    @Default
    @CommandPermission("tkits.use")
    @Description("Opens the main kit selection GUI.")
    public void onKitCommand(Player player) {
        // PlayerListener handles loading data, but quick check doesn't hurt
        if (plugin.getPlayerDataManager().getPlayerData(player) == null) {
            msg.sendMessage(player, "data_loading_please_wait");
            return;
        }
        guiManager.openMainMenu(player);
    }

    @Subcommand("import")
    @CommandCompletion("@nothing") // Don't suggest anything for the code
    @Syntax("<code>")
    @Description("Imports a kit using a share code into the first available slot.")
    @CommandPermission("tkits.kit.share")
    public void onKitImport(Player player, String code) {

        // --- Combat Tag Check ---
        if (plugin.getCombatTagManager().isTagged(player)) {
            long remainingMillis = plugin.getCombatTagManager().getRemainingTagTimeMillis(player);
            double remainingSeconds = Math.ceil(remainingMillis / 1000.0);
            msg.sendMessage(player, "in_combat", "time", String.format("%.1f", remainingSeconds));
            msg.playSound(player, "error");
            return;
        }

        // --- Cooldown Check --- 
        // Assuming a general 'kit_action' or 'kit_import' cooldown group exists
        // Use the standard KIT_LOAD cooldown for importing for simplicity
        CooldownService.CooldownType cooldownType = CooldownService.CooldownType.KIT_LOAD; 

        long remainingCooldown = plugin.getCooldownService().getRemainingCooldown(player.getUniqueId(), cooldownType);
        if (remainingCooldown > 0) {
            msg.sendMessage(player, "on_cooldown", "time", String.format("%.1f", remainingCooldown / 1000.0));
            msg.playSound(player, "cooldown");
            return;
        }

        if (code == null) {
             // ACF might handle this depending on config, but good practice to check
             msg.sendMessage(player, "import_fail_invalid_code"); // Use a more specific message? "Code cannot be empty"?
             msg.playSound(player, "error");
             return;
         }

        final int expectedCodeLength = plugin.getConfigManager().getMainConfig().getInt("sharing.code_length", 5);
         String upperCode = code.trim().toUpperCase(); // Trim whitespace and normalize case

         if (upperCode.length() != expectedCodeLength) {
             msg.sendMessage(player, "import_fail_invalid_code");
             msg.playSound(player, "error");
             return;
        }

        final int maxKits = plugin.getConfigManager().getMainConfig().getInt("kits.max_kits_per_player", 7);
        final PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
        if(playerData == null) {
             msg.sendMessage(player, "data_loading_please_wait"); // Data still loading?
            return;
        }

        // Find first empty slot
        int targetSlot = -1;
        for (int i = 1; i <= maxKits; i++) {
            if (!playerData.hasKit(i)) {
                targetSlot = i;
                break;
            }
        }

        if (targetSlot == -1) {
            msg.sendMessage(player, "import_fail_no_slot");
            msg.playSound(player, "error");
            return;
        }

        final int finalTargetSlot = targetSlot;
        msg.sendActionBar(player, "importing_kit"); // Use action bar

         plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Kit importedKit = plugin.getShareCodeManager().redeemShareCode(upperCode);

             plugin.getServer().getScheduler().runTask(plugin, () -> { // Back to main thread
                if (importedKit != null) {
                    // Kit needs owner, number, and global status reset for the importer
                    Kit kitToSave = importedKit.toBuilder()
                         .kitNumber(finalTargetSlot)
                         .owner(player.getUniqueId())
                         .global(false) // Imports are always private initially
                         .build();
                    // Ensure EChest consistency (null safe copy)
                     kitToSave = kitToSave.toBuilder()
                         .enderChestContents(com.takeda.tkits.models.KitContents.deserialize(com.takeda.tkits.models.KitContents.serialize(importedKit.getEnderChestContents())))
                         .build();

                    playerData.setKit(finalTargetSlot, kitToSave);
                    plugin.getPlayerDataManager().savePlayerKit(player.getUniqueId(), kitToSave)
                         .thenRun(() -> { // Only message success after save completes
                             msg.sendActionBar(player, "import_success", "kit_number", String.valueOf(finalTargetSlot)); // Use action bar
                             msg.playSound(player, "kit_import_success");
                             // Set cooldown AFTER successful import
                             plugin.getCooldownService().applyCooldown(player.getUniqueId(), cooldownType);
                             // Optional: Open the kit editor for the newly imported kit?
                             // guiManager.openKitEditor(player, finalTargetSlot);
                         })
                         .exceptionally(ex -> { // Handle potential save errors
                              msg.sendMessage(player, "import_fail_error");
                              msg.playSound(player, "error");
                              plugin.getMessageUtil().logException("Error saving imported kit via command", ex);
                              // Revert memory state if save failed?
                              playerData.removeKit(finalTargetSlot);
                              return null;
                          });
                } else {
                    msg.sendMessage(player, "import_fail_invalid_code"); // Keep error in chat
                    msg.playSound(player, "kit_import_fail");
                }
            }); // End main thread task
         }); // End async task
    }
}
