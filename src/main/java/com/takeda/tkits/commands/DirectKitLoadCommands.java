package com.takeda.tkits.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.CombatTagManager;
import com.takeda.tkits.managers.KitManager;
import com.takeda.tkits.services.CooldownService;
import org.bukkit.entity.Player;

// Handles /k1, /k2, ... /k7
public class DirectKitLoadCommands extends BaseCommand {

    private final TKits plugin;
    private final KitManager kitManager;
    private final CombatTagManager combatTagManager; // Needed for combat check
    private final CooldownService cooldownService;   // Needed for cooldown check

    public DirectKitLoadCommands(TKits plugin) {
        this.plugin = plugin;
        this.kitManager = plugin.getKitManager();
        this.combatTagManager = plugin.getCombatTagManager();
        this.cooldownService = plugin.getCooldownService();
    }

    // Consolidated logic for loading, called by specific command handlers
    private void attemptLoadKit(Player player, int kitNumber) {
        // Permission check already handled by ACF annotation before method is called

        // 1. Check Player Data
        if (plugin.getPlayerDataManager().getPlayerData(player) == null) {
             plugin.getMessageUtil().sendMessage(player, "data_loading_please_wait");
            return;
        }

        // 2. Check Combat Tag
         if (combatTagManager.isTagged(player) && !player.hasPermission("tkits.admin")) {
             long remaining = combatTagManager.getRemainingTagTimeMillis(player);
             plugin.getMessageUtil().sendMessage(player, "in_combat", "time", String.format("%.1f", remaining / 1000.0));
             plugin.getMessageUtil().playSound(player, "error");
             return;
         }

        // 3. Check Cooldown (KitManager's loadKit handles this internally now)
        // Redundant check removed here, let kitManager handle it for consistency

        // 4. Attempt to load
        kitManager.loadKit(player, kitNumber); // This method now checks cooldown and handles messages/sounds
    }

    // --- Individual Command Handlers ---

    @CommandAlias("k1") @Description("Loads Kit 1") @CommandPermission("tkits.load.1") @Default
    public void onKit1(Player player) { attemptLoadKit(player, 1); }

    @CommandAlias("k2") @Description("Loads Kit 2") @CommandPermission("tkits.load.2") @Default
    public void onKit2(Player player) { attemptLoadKit(player, 2); }

    @CommandAlias("k3") @Description("Loads Kit 3") @CommandPermission("tkits.load.3") @Default
    public void onKit3(Player player) { attemptLoadKit(player, 3); }

    @CommandAlias("k4") @Description("Loads Kit 4") @CommandPermission("tkits.load.4") @Default
    public void onKit4(Player player) { attemptLoadKit(player, 4); }

    @CommandAlias("k5") @Description("Loads Kit 5") @CommandPermission("tkits.load.5") @Default
    public void onKit5(Player player) { attemptLoadKit(player, 5); }

    @CommandAlias("k6") @Description("Loads Kit 6") @CommandPermission("tkits.load.6") @Default
    public void onKit6(Player player) { attemptLoadKit(player, 6); }

    @CommandAlias("k7") @Description("Loads Kit 7") @CommandPermission("tkits.load.7") @Default
    public void onKit7(Player player) { attemptLoadKit(player, 7); }
}