package com.takeda.tkits.util;

import com.takeda.tkits.TKits;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.stream.Collectors;

public class GuiUtils {

    // Static serializer instance for efficiency within this utility class
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand().toBuilder().hexColors().build();

    // Optional: Key to mark GUI items persistently if strict checks are ever needed
    // public static final NamespacedKey GUI_ITEM_TAG = new NamespacedKey(TKits.getInstance(), "gui_item_marker");

    /**
     * Creates a basic GUI ItemStack with specified material, name, lore, and optional custom model data.
     * Automatically disables italics on name and lore.
     *
     * @param material The Material of the item.
     * @param name     The Component name of the item.
     * @param lore     A List of Components for the item's lore. Can be null or empty.
     * @param modelData The CustomModelData value (set only if > 0).
     * @return The created ItemStack.
     */
    public static ItemStack createGuiItem(Material material, Component name, List<Component> lore, int modelData) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Disable italics by default for GUI items
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream()
                              .map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                              .collect(Collectors.toList()));
            }
            if (modelData > 0) {
                meta.setCustomModelData(modelData);
            }
            // Example persistent tag (uncomment if needed)
            // meta.getPersistentDataContainer().set(GUI_ITEM_TAG, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Creates a GUI ItemStack using legacy string formatting for name and lore.
     *
     * @param material    The Material.
     * @param legacyName  The legacy formatted name string (using & codes).
     * @param legacyLore  A List of legacy formatted lore strings. Can be null or empty.
     * @param modelData   The CustomModelData value.
     * @return The created ItemStack.
     */
    public static ItemStack createGuiItem(Material material, String legacyName, List<String> legacyLore, int modelData) {
         Component nameComponent = legacyName == null ? Component.empty() : LEGACY_SERIALIZER.deserialize(legacyName);
         List<Component> loreComponents = (legacyLore == null || legacyLore.isEmpty()) ? null : legacyLore.stream()
             .map(LEGACY_SERIALIZER::deserialize)
             .collect(Collectors.toList());
         return createGuiItem(material, nameComponent, loreComponents, modelData);
     }

     // Overload for convenience without lore or model data
     public static ItemStack createGuiItem(Material material, String legacyName) {
        return createGuiItem(material, legacyName, null, 0);
    }
     // Overload for convenience without model data
     public static ItemStack createGuiItem(Material material, String legacyName, List<String> legacyLore) {
         return createGuiItem(material, legacyName, legacyLore, 0);
     }


    /**
     * Creates a Player Head ItemStack for use in GUIs.
     *
     * @param owner The OfflinePlayer whose skin will be displayed.
     * @param name  The Component name of the item.
     * @param lore  A List of Components for the item's lore.
     * @return The created Player Head ItemStack.
     */
     public static ItemStack createPlayerHead(OfflinePlayer owner, Component name, List<Component> lore) {
         ItemStack item = new ItemStack(Material.PLAYER_HEAD);
         // Ensure owner isn't null to avoid errors
         if (owner == null) {
             TKits.getInstance().getLogger().warning("Attempted to create player head with null owner!");
             ItemMeta meta = item.getItemMeta();
             if(meta != null) meta.displayName(name.colorIfAbsent(NamedTextColor.RED).append(Component.text(" (Error: Null Owner)")));
             item.setItemMeta(meta);
             return item; // Return head with error name or just a default skull?
         }

         SkullMeta meta = (SkullMeta) item.getItemMeta();
         if (meta != null) {
             meta.setOwningPlayer(owner); // Use setOwningPlayer for better compatibility
             meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
             if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream()
                              .map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                              .collect(Collectors.toList()));
             }
             // meta.getPersistentDataContainer().set(GUI_ITEM_TAG, PersistentDataType.BYTE, (byte) 1); // Optional Tagging
            item.setItemMeta(meta);
         }
         return item;
     }

     public static ItemStack createPlayerHead(OfflinePlayer owner, String legacyName, List<String> legacyLore) {
        Component nameComponent = legacyName == null ? Component.empty() : LEGACY_SERIALIZER.deserialize(legacyName);
        List<Component> loreComponents = (legacyLore == null || legacyLore.isEmpty()) ? null : legacyLore.stream()
             .map(LEGACY_SERIALIZER::deserialize)
             .collect(Collectors.toList());
        return createPlayerHead(owner, nameComponent, loreComponents);
    }


    /**
     * Fills the background of an inventory array with a specified filler item.
     * Ignores slots that already contain an item.
     *
     * @param contents The ItemStack array representing the inventory contents.
     * @param fillMaterial The Material to use for the filler item (e.g., GRAY_STAINED_GLASS_PANE). Use AIR or null to disable filling.
     */
     public static void fillBackground(ItemStack[] contents, Material fillMaterial) {
         if (fillMaterial == null || fillMaterial == Material.AIR || fillMaterial.isLegacy()) { // Check legacy too
            return;
         }
         // Create the filler item once for efficiency
         ItemStack filler = createGuiItem(fillMaterial, Component.empty(), null, 0); // No name, no lore
         for (int i = 0; i < contents.length; i++) {
             if (contents[i] == null || contents[i].getType() == Material.AIR) {
                 contents[i] = filler.clone(); // Use clone to avoid modifying the original filler object if meta was changed elsewhere
             }
         }
     }
}