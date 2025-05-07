package com.takeda.tkits.listeners;

import com.takeda.tkits.TKits;
import com.takeda.tkits.managers.GuiManager;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.util.MessageUtil;

import net.kyori.adventure.text.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class GuiListener implements Listener {

    private final TKits plugin;
    private final GuiManager guiManager;
    private final MessageUtil msg;
    private final NamespacedKey actionKey;
    private final NamespacedKey kitNumberKey;
    private final NamespacedKey kitOwnerKey;
    private final Map<UUID, Long> kitroomCooldown = new ConcurrentHashMap<>();

    public GuiListener(TKits plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager();
        this.msg = plugin.getMessageUtil();
        this.actionKey = guiManager.getActionKey();
        this.kitNumberKey = guiManager.getKitNumberKey();
        this.kitOwnerKey = guiManager.getKitOwnerKey();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Inventory clickedInventory = event.getClickedInventory();
        Inventory topInventory = event.getView().getTopInventory();

        
        if (!(topInventory.getHolder() instanceof GuiManager)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topInvSize = topInventory.getSize();
        boolean isTopInventoryClick = rawSlot >= 0 && rawSlot < topInvSize;
        boolean isPlayerInventoryClick = rawSlot >= topInvSize;

        
        if (isPlayerInventoryClick) {
            
            if (event.isShiftClick() &&
                !(guiManager.isKitEditorInventory(topInventory) ||
                  guiManager.isEnderChestEditorInventory(topInventory) ||
                  (guiManager.isKitroomCategoryInventory(topInventory) && player.hasPermission("tkits.kitroom.admin"))
                 )
               )
            {
                event.setCancelled(true);
                
                return;
            }
            
            return; 
        }

        
        if (isTopInventoryClick) {
            ItemStack clickedItem = event.getCurrentItem();
            int slot = event.getSlot();

            
            event.setCancelled(true);
            

            
            if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                ItemMeta meta = clickedItem.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    if (pdc.has(actionKey, PersistentDataType.STRING)) {
                        String action = pdc.get(actionKey, PersistentDataType.STRING);
                        msg.debug("  -> Button Action Found! Action: '" + action + "', Slot: " + slot + ", Item: " + clickedItem.getType());
                        if (action != null) {
                            
                            guiManager.handleButtonAction(player, action, pdc, event.getClick(), slot);
                        } else {
                             msg.debug("  -> Null action found on button? Remaining cancelled.");
                        }
                        return; 
                    }
                }
            } 

            

            
            if (guiManager.isKitEditorInventory(topInventory) || guiManager.isEnderChestEditorInventory(topInventory)) {
                int maxEditableSlot = guiManager.isKitEditorInventory(topInventory) ? 40 : 26;
                if (slot >= 0 && slot <= maxEditableSlot) {
                    
                    
                    event.setCancelled(false); 
                } else {
                    
                    
                    
                }
                return; 
            }
            
            else if (guiManager.isKitroomCategoryInventory(topInventory)) {
                 boolean isAdmin = player.hasPermission("tkits.kitroom.admin");
                 if (slot >= 0 && slot < 45) { 
                     if (isAdmin) {
                          
                          
                          plugin.getConfigManager().markKitroomChanged(); 
                          event.setCancelled(false); 
                     } else {
                          
                          if (clickedItem != null && clickedItem.getType() != Material.AIR && !guiManager.isKitroomFiller(clickedItem)) {
                              
                              handleKitroomCategoryNonButtonClick(event, player, clickedInventory, topInventory);
                          } else {
                               
                          }
                          
                     }
                 } else {
                      
                      
                      
                 }
                return; 
            }
            
            else if (guiManager.isGlobalListInventory(topInventory)) {
                 
                 if (clickedItem != null && clickedItem.getType() == Material.PLAYER_HEAD) {
                      
                      handleGlobalListNonButtonClick(event, player, clickedInventory, topInventory);
                 } else {
                      
                 }
                 
                 return; 
            }
            
            else if (guiManager.isPersonalKitChoiceInventory(topInventory)) {
                
                
                
                if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                    ItemMeta meta = clickedItem.getItemMeta();
                    if (meta != null) {
                        PersistentDataContainer pdc = meta.getPersistentDataContainer();
                        
                        if (pdc.has(kitNumberKey, PersistentDataType.INTEGER) && !pdc.has(actionKey, PersistentDataType.STRING)) {
                            int kitNumber = pdc.getOrDefault(kitNumberKey, PersistentDataType.INTEGER, -1);
                            if (kitNumber > 0) {
                                msg.debug("    -> Kit item clicked: Kit " + kitNumber);
                                PlayerData playerData = plugin.getPlayerDataManager().getPlayerData(player);
                                Kit selectedKit = (playerData != null) ? playerData.getKit(kitNumber) : null;
                                
                                Kit kitSnapshot = (selectedKit != null) ? selectedKit.toBuilder().build() : null;

                                if (kitSnapshot != null) {
                                    
                                    guiManager.cancelPendingKitChoice(player.getUniqueId(), Optional.of(kitSnapshot));
                                    msg.playSound(player, "gui_click"); 
                                    player.closeInventory(); 
                                } else {
                                    
                                    msg.sendMessage(player, "kit_not_found", "kit_number", String.valueOf(kitNumber));
                                    msg.playSound(player, "error");
                                    guiManager.cancelPendingKitChoice(player.getUniqueId(), Optional.empty()); 
                                    player.closeInventory();
                                }
                            } else {
                                 msg.debug("    -> Kit item clicked but invalid kit number in PDC.");
                            }
                        } else {
                             
                        }
                    }
                }
                
                return;
            }
            

            
            
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        InventoryView view = event.getView();
        Inventory topInventory = view.getTopInventory();
        if (!(topInventory.getHolder() instanceof GuiManager)) return;

        Player player = (Player) event.getWhoClicked();
        boolean isAdminKitroomCategory = guiManager.isKitroomCategoryInventory(topInventory) && player.hasPermission("tkits.kitroom.admin");
        boolean isEditor = guiManager.isKitEditorInventory(topInventory) || guiManager.isEnderChestEditorInventory(topInventory);

        boolean draggedIntoTop = false;
        int highestRawSlotInTop = -1;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topInventory.getSize()) {
                draggedIntoTop = true;
                highestRawSlotInTop = Math.max(highestRawSlotInTop, rawSlot);
            }
        }

        if (draggedIntoTop) {
            
            if (isEditor) {
                int maxEditableSlot = guiManager.isKitEditorInventory(topInventory) ? 40 : 26;
                if (highestRawSlotInTop > maxEditableSlot) {
                    
                    event.setCancelled(true);
                } else {
                    
                }
            } else if (isAdminKitroomCategory) {
                if (highestRawSlotInTop >= 45) { 
                    
                    event.setCancelled(true);
                } else { 
                    
                    plugin.getConfigManager().markKitroomChanged();
                }
            } else { 
                
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory closedInventory = event.getInventory();
        if (!(closedInventory.getHolder() instanceof GuiManager)) return;
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            
            
            guiManager.handleGuiClose(player, closedInventory);
        }
    }

    
    private void handleKitroomCategoryNonButtonClick(InventoryClickEvent event, Player player, Inventory clickedInventory, Inventory topInventory) {
        int slot = event.getSlot();
        UUID playerUUID = player.getUniqueId();
        boolean isAdmin = player.hasPermission("tkits.kitroom.admin");

        
        if (!isAdmin && slot >= 0 && slot < 45) {
            ItemStack clickedItem = event.getCurrentItem();
            if(clickedItem == null || clickedItem.getType() == Material.AIR || guiManager.isKitroomFiller(clickedItem)) {
                
                return;
            }
            
            boolean requiresPerm = plugin.getConfigManager().isKitroomRequirePerCategoryPermission();
            String category = guiManager.getEditingKitroomCategoryMap().get(playerUUID);
            if (requiresPerm && category != null && !category.isEmpty()) {
                String permNode = "tkits.kitroom.category." + category.toLowerCase();
                if (!player.hasPermission(permNode)) {
                    msg.sendMessage(player, "no_permission_category", "category", guiManager.capitalize(category));
                    msg.playSound(player, "error");
                    return;
                }
            } else if (requiresPerm && (category == null || category.isEmpty())) {
                msg.logWarning("Kitroom permission check enabled, but couldn't get category for player " + player.getName());
                msg.sendMessage(player, "error");
                return;
            }
            int cooldownSeconds = plugin.getConfigManager().getKitroomItemTakeCooldownSeconds();
            if (cooldownSeconds > 0 && !player.hasPermission("tkits.cooldown.bypass")) {
                long now = System.currentTimeMillis();
                long lastTake = kitroomCooldown.getOrDefault(playerUUID, 0L);
                long remainingMillis = (lastTake + (cooldownSeconds * 1000L)) - now;
                if (remainingMillis > 0) {
                     msg.sendMessage(player, "kitroom_on_cooldown", "time", String.format("%.1f", remainingMillis / 1000.0));
                     msg.playSound(player, "cooldown");
                     return;
                }
            }
            ItemStack itemToGive = clickedItem.clone();
            
            ItemMeta giveMeta = itemToGive.getItemMeta();
            if(giveMeta != null && giveMeta.hasLore()){
                List<Component> currentLore = giveMeta.lore();
                if(currentLore != null){
                    
                    if(!currentLore.isEmpty() && currentLore.get(currentLore.size() - 1).equals(guiManager.KITROOM_PLAYER_LORE_HINT)) {
                        currentLore.remove(currentLore.size() - 1);
                        
                        
                        if(!currentLore.isEmpty() && plugin.getMessageUtil().getPlainTextSerializer().serialize(currentLore.get(currentLore.size() - 1)).trim().isEmpty()) {
                            currentLore.remove(currentLore.size() - 1);
                        }
                        giveMeta.lore(currentLore);
                        itemToGive.setItemMeta(giveMeta);
                    }
                }
            }

            Map<Integer, ItemStack> leftover = player.getInventory().addItem(itemToGive);
             if (!leftover.isEmpty()) {
                 msg.sendActionBar(player, "inventory_full_item_dropped");
                 leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
             }
             msg.sendActionBar(player, "kitroom_item_given", "amount", String.valueOf(itemToGive.getAmount()), "item_name", getItemName(itemToGive));
             msg.playSound(player, "kitroom_item_take");
             if (cooldownSeconds > 0 && !player.hasPermission("tkits.cooldown.bypass")) {
                kitroomCooldown.put(playerUUID, System.currentTimeMillis());
             }
        }
    }

     private void handleGlobalListNonButtonClick(InventoryClickEvent event, Player player, Inventory clickedInventory, Inventory topInventory) {
         ItemStack clickedItem = event.getCurrentItem();
         if (clickedItem == null || clickedItem.getType() != Material.PLAYER_HEAD) return;
         ItemMeta meta = clickedItem.getItemMeta();
         if (meta != null) {
              PersistentDataContainer pdc = meta.getPersistentDataContainer();
              if (pdc.has(kitOwnerKey, PersistentDataType.STRING) && pdc.has(kitNumberKey, PersistentDataType.INTEGER)) {
                  UUID ownerUUID = null;
                  int kitNumber = pdc.getOrDefault(kitNumberKey, PersistentDataType.INTEGER, -1);
                  try { ownerUUID = UUID.fromString(pdc.get(kitOwnerKey, PersistentDataType.STRING)); } catch (Exception ignored) {}
                  if (ownerUUID == null || kitNumber <= 0) { msg.logWarning("Invalid PDC data on global kit player head"); return; }
                  final UUID finalOwnerUUID = ownerUUID;
                  final int finalKitNumber = kitNumber;
                  if (event.isLeftClick()) {
                      msg.sendActionBar(player, "loading_kit", "kit_number", String.valueOf(finalKitNumber));
                      plugin.getApi().getPlayerKitAsync(finalOwnerUUID, finalKitNumber).thenAcceptAsync(optionalKit -> {
                           if (optionalKit.isPresent()) {
                               Kit kitToLoad = optionalKit.get();
                               if (!kitToLoad.isGlobal()) {
                                    msg.sendMessage(player, "kit_no_longer_global"); msg.playSound(player, "error");
                                    guiManager.openGlobalKitsList(player, guiManager.getPlayerGlobalKitPagesMap().getOrDefault(player.getUniqueId(), 1));
                                    return;
                               }
                               boolean success = plugin.getKitManager().loadKit(player, kitToLoad);
                               if (success) player.closeInventory();
                           } else {
                                msg.sendMessage(player, "kit_not_found", "kit_number", String.valueOf(finalKitNumber)); msg.playSound(player, "error");
                                guiManager.openGlobalKitsList(player, guiManager.getPlayerGlobalKitPagesMap().getOrDefault(player.getUniqueId(), 1));
                           }
                       }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
                  } else if (event.isRightClick()) {
                      
                      guiManager.handleButtonAction(player, "preview_global_kit", pdc, event.getClick(), event.getSlot());
                  }
              }
          }
     }

    private String getItemName(ItemStack item) {
        if (item == null) return "Nothing";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component displayName = meta.displayName();
            if (displayName != null) {
                
                return plugin.getMessageUtil().getPlainTextSerializer().serialize(displayName);
            }
        }
        
        return Arrays.stream(item.getType().toString().toLowerCase().split("_"))
                     .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1))
                     .collect(Collectors.joining(" "));
    }
} 