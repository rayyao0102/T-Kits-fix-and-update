package com.takeda.tkits.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import com.takeda.tkits.TKits;
import com.takeda.tkits.api.TKitsAPI; 
import com.takeda.tkits.managers.CombatTagManager;
import com.takeda.tkits.services.CooldownService; 
import com.takeda.tkits.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;

import java.util.Map;


@Description("Utility kit commands like Regear and Arrange")
public class UtilityCommands extends BaseCommand {

    private final TKits plugin;
    private final CombatTagManager combatTagManager;
    private final CooldownService cooldownService; 
    private final MessageUtil msg;
    private final TKitsAPI api; 

    public UtilityCommands(TKits plugin) {
        this.plugin = plugin;
        this.combatTagManager = plugin.getCombatTagManager();
        this.cooldownService = plugin.getCooldownService(); 
        this.msg = plugin.getMessageUtil();
        this.api = plugin.getApi(); 
    }

    @CommandAlias("regear|rg")
    @CommandPermission("tkits.regear")
    @Description("Gives a special shulker box to regear your inventory.")
    public void onRegear(Player player) {
         if (checkCombatTag(player)) return;
         if (checkCooldown(player, CooldownService.CooldownType.REGEAR)) return;

         ItemStack regearBox = api.getRegearTriggerItem(); 

         
         if (regearBox != null && regearBox.getType() != Material.AIR) {
             ItemMeta meta = regearBox.getItemMeta();
             if (meta != null) {
                 String rawName = plugin.getConfigManager().getRegearBoxName();
                 String finalName = rawName.replace("{player}", player.getName());
                 meta.displayName(msg.deserialize(finalName)); 
                 regearBox.setItemMeta(meta);
             }
         } else {
             msg.sendMessage(player, "error"); 
             plugin.getMessageUtil().logWarning("Failed to get valid Regear Trigger Item from API for /regear command.");
             return;
         }

         
         Map<Integer, ItemStack> leftover = player.getInventory().addItem(regearBox);
         if(!leftover.isEmpty()){
             msg.sendMessage(player, "inventory_full_item_dropped"); 
             leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
         } else {
              msg.sendActionBar(player, "regear_shulker_given"); 
              msg.playSound(player, "gui_click"); 
         }
         
         
    }

    @CommandAlias("arrange|ag")
    @CommandPermission("tkits.arrange")
    @Description("Arranges your inventory according to your last loaded kit.")
    public void onArrange(Player player) {
        if (checkCombatTag(player)) return;
         
         

        
        api.performArrange(player);
    }

    
    private boolean checkCombatTag(Player player) {
        if (combatTagManager.isTagged(player) && !player.hasPermission("tkits.admin")) { 
            long remaining = combatTagManager.getRemainingTagTimeMillis(player);
            msg.sendMessage(player, "in_combat", "time", String.format("%.1f", remaining / 1000.0));
            msg.playSound(player, "error");
            return true;
        }
        return false;
    }

     
     private boolean checkCooldown(Player player, CooldownService.CooldownType type) {
         if (!player.hasPermission("tkits.cooldown.bypass")) {
             long remaining = cooldownService.getRemainingCooldown(player.getUniqueId(), type);
             if (remaining > 0) {
                 msg.sendMessage(player, "on_cooldown", "time", String.format("%.1f", remaining / 1000.0));
                 msg.playSound(player, "cooldown");
                 return true; 
             }
         }
         return false; 
     }
}
