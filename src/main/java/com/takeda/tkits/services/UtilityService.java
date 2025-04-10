package com.takeda.tkits.services;

import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

import java.util.*;
import java.util.stream.Collectors;

public class UtilityService {

    private final TKits plugin;
    private static final Set<Material> REFILLABLE_MATERIALS = EnumSet.of(
            Material.EXPERIENCE_BOTTLE, Material.ARROW, Material.SPECTRAL_ARROW,
            Material.END_CRYSTAL, Material.OBSIDIAN, Material.ENDER_PEARL,
            Material.GLOWSTONE, Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE,
            Material.RESPAWN_ANCHOR, // Common CPvP items
            Material.CHORUS_FRUIT, Material.TNT
    );

    public UtilityService(TKits plugin) {
        this.plugin = plugin;
    }

    public boolean executeRegear(Player player, Kit kit) {
        if (kit == null || kit.getContents() == null) {
             plugin.getMessageUtil().sendMessage(player, "error");
            return false;
        }
        plugin.getMessageUtil().sendActionBar(player, "regear_started");

        PlayerInventory inv = player.getInventory();
        Map<Integer, ItemStack> kitItemsMap = kit.getContents().getItems();
        List<ItemStack> itemsToAddLater = new ArrayList<>();
        boolean needsUpdate = false;

        for (int i = 0; i < 41; i++) {
             ItemStack currentItem = inv.getItem(i);
             ItemStack kitItem = kitItemsMap.get(i);

             if (kitItem == null || kitItem.getType() == Material.AIR) {
                 continue; // Kit doesn't define this slot
             }

             if (currentItem == null || currentItem.getType() == Material.AIR) {
                 itemsToAddLater.add(kitItem.clone()); // Player slot is empty, add kit item later
                 needsUpdate = true;
                 continue;
             }

            if (isSameItemTypeAndMeta(currentItem, kitItem)) {
                 boolean itemChanged = false;
                 // Handle refillable stack sizes
                 if (REFILLABLE_MATERIALS.contains(currentItem.getType())) {
                     if (currentItem.getAmount() < kitItem.getAmount()) {
                         currentItem.setAmount(kitItem.getAmount());
                         itemChanged = true;
                     }
                 }
                 // Handle item durability repair
                 else if (currentItem.getItemMeta() instanceof Damageable) {
                     Damageable currentDamageable = (Damageable) currentItem.getItemMeta();
                      if (currentDamageable.hasDamage()) {
                         currentDamageable.setDamage(0);
                         currentItem.setItemMeta(currentDamageable);
                         itemChanged = true;
                      }
                 }
                  // Handle other stackable items (e.g., basic blocks not in refill list, potions)
                  else if (currentItem.getType().getMaxStackSize() > 1 && currentItem.getAmount() < kitItem.getAmount()) {
                      // For non-refillable stackables, only increase amount if lower, don't decrease
                      // We rely on kitItem amount being the desired 'max' for this slot
                       currentItem.setAmount(kitItem.getAmount());
                      itemChanged = true;
                  }
                  if (itemChanged) needsUpdate = true;

            } else {
                 // Items are different. Add the required kit item to be placed later if possible.
                 itemsToAddLater.add(kitItem.clone());
                 needsUpdate = true;
            }
        }

        // Add missing items if any
         if (!itemsToAddLater.isEmpty()) {
            Map<Integer, ItemStack> couldNotFit = inv.addItem(itemsToAddLater.toArray(new ItemStack[0]));
            if (!couldNotFit.isEmpty()) {
                plugin.getMessageUtil().sendMessage(player, "inventory_full_items_dropped");
                couldNotFit.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            }
         }

         if(needsUpdate) {
             player.updateInventory();
         }
        // Success message usually handled by caller (InteractionListener)
        return true;
    }

    public boolean executeArrange(Player player, Kit kit) {
        if (kit == null || kit.getContents() == null) {
            plugin.getMessageUtil().sendMessage(player, "error");
            return false;
        }
        plugin.getMessageUtil().sendActionBar(player, "arrange_started");

        PlayerInventory inv = player.getInventory();
        Map<Integer, ItemStack> kitLayoutMap = kit.getContents().getItems();
        List<ItemStack> currentItems = new ArrayList<>();

        // --- Step 1: Store current and clear inventory ---
        // Store armor separately to handle later
        ItemStack[] currentArmor = inv.getArmorContents().clone(); // Clone for safety
        ItemStack currentOffhand = inv.getItemInOffHand() == null ? null : inv.getItemInOffHand().clone();

        for (int i = 0; i < 36; i++) {
             ItemStack item = inv.getItem(i);
             if (item != null && item.getType() != Material.AIR) {
                currentItems.add(item.clone()); // Store clones
            }
            inv.clear(i); // Clear slot
        }
         inv.setArmorContents(new ItemStack[4]); // Clear armor slots in inventory object
         inv.setItemInOffHand(null);              // Clear offhand

         // Add armor/offhand to the list for potential rearrangement if they match layout
          Arrays.stream(currentArmor).filter(Objects::nonNull).forEach(currentItems::add);
         if(currentOffhand != null) currentItems.add(currentOffhand);

        // --- Step 2: Place items according to kit layout ---
        List<ItemStack> remainingItems = new LinkedList<>(currentItems); // Use LinkedList for efficient removal

        // Sort kit slots to process main inventory, then armor, then offhand
        List<Integer> sortedSlots = kitLayoutMap.keySet().stream().sorted().collect(Collectors.toList());

        for (int targetSlot : sortedSlots) {
             ItemStack kitItemTemplate = kitLayoutMap.get(targetSlot);
             if(kitItemTemplate == null) continue;

             ItemStack itemToPlace = null;
             ListIterator<ItemStack> iter = remainingItems.listIterator();
             while (iter.hasNext()) {
                 ItemStack current = iter.next();
                 if (isSameItemTypeAndMeta(current, kitItemTemplate)) { // Find a matching item
                     itemToPlace = current;
                     iter.remove(); // Remove from remaining list
                     break;
                 }
             }

            if (itemToPlace != null) {
                setItemSafe(inv, targetSlot, itemToPlace); // Place found item in target slot
             }
        }

        // --- Step 3: Add remaining items back ---
        if (!remainingItems.isEmpty()) {
             Map<Integer, ItemStack> couldNotFit = inv.addItem(remainingItems.toArray(new ItemStack[0]));

             if (!couldNotFit.isEmpty()) {
                String handleMode = plugin.getConfigManager().getArrangeHandleExtraItems();
                switch (handleMode) {
                    case "DROP":
                        plugin.getMessageUtil().sendMessage(player, "inventory_full_items_dropped_arrange");
                         couldNotFit.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                        break;
                    case "MOVE_EMPTY":
                        // Try to move to first available empty slot, drop if still no space
                         List<ItemStack> stillCannotFit = new ArrayList<>();
                         for (ItemStack itemToMove : couldNotFit.values()) {
                             int firstEmpty = inv.firstEmpty();
                             if (firstEmpty != -1) {
                                 inv.setItem(firstEmpty, itemToMove);
                             } else {
                                 stillCannotFit.add(itemToMove);
                             }
                         }
                         if (!stillCannotFit.isEmpty()) {
                             plugin.getMessageUtil().sendMessage(player, "inventory_full_items_dropped_arrange"); // Still need drop message
                             stillCannotFit.forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                         }
                        break;
                    case "KEEP": // Default behaviour: Do nothing, items remain in their slots if possible
                    default:
                        // If KEEP mode, the `addItem` already put them back if possible.
                        // If it failed, they are already in the couldNotFit map but we don't drop them.
                         plugin.getMessageUtil().sendMessage(player, "inventory_arrange_kept_extras"); // Add a message indicating extras were kept/ignored
                        break;
                }
             }
        }

        player.updateInventory();
        plugin.getMessageUtil().sendActionBar(player, "arrange_success");
        plugin.getMessageUtil().playSound(player, "arrange_success");
        return true;
    }


    /**
     * Checks if two ItemStacks are sufficiently similar for regear/arrange logic.
     * Currently uses Bukkit's `isSimilar`, which checks type, amount, durability,
     * and meta (name, lore, enchants, etc.). Can be customized for stricter/looser matching.
     * @param item1 First item
     * @param item2 Second item
     * @return True if considered similar enough, false otherwise.
     */
     private boolean isSameItemTypeAndMeta(ItemStack item1, ItemStack item2) {
         if (item1 == null || item2 == null) return item1 == item2; // Both null are same, one null isn't
         // Basic Bukkit check is a good start, may need refinement for specific cases like potions later
         return item1.isSimilar(item2);
         /* Example stricter check (ignoring amount for type matching):
         if (item1.getType() != item2.getType()) return false;
         ItemMeta meta1 = item1.getItemMeta();
         ItemMeta meta2 = item2.getItemMeta();
         if (meta1 == null && meta2 == null) return true; // Both null meta is similar type-wise
         if (meta1 == null || meta2 == null) return false; // One null meta isn't similar
         // Add checks for name, lore, enchants, potion data, NBT tags etc. as needed
         return meta1.equals(meta2); // Default ItemMeta.equals comparison
         */
     }

      // Helper method (copied from KitManager/original spot)
     private void setItemSafe(PlayerInventory inv, int slot, ItemStack item) {
        if (item == null || slot < 0 || slot > 40) return;
        switch (slot) {
            case 36: inv.setBoots(item); break;
            case 37: inv.setLeggings(item); break;
            case 38: inv.setChestplate(item); break;
            case 39: inv.setHelmet(item); break;
            case 40: inv.setItemInOffHand(item); break;
            default: inv.setItem(slot, item); break;
        }
    }
}