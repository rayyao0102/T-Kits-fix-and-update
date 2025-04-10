package com.takeda.tkits.util;

import com.takeda.tkits.TKits; // Use main class for logging
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder; // Import for Base64

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for serializing and deserializing ItemStacks
 * using Bukkit's Object Streams and Base64 encoding.
 * Primarily used for storing items reliably in configuration files like kitroom.yml.
 */
public class ItemSerialization {

    /**
     * Serializes a single ItemStack into a Base64 encoded string.
     *
     * @param item The ItemStack to serialize. Can be null.
     * @return The Base64 encoded string representation, or null if the input item was null or AIR.
     * @throws IllegalStateException If serialization fails due to an IOException or other errors.
     */
    public static String itemStackToBase64(ItemStack item) throws IllegalStateException {
        // Return null for null or AIR items to save space/avoid unnecessary data
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {

            // Write the single ItemStack object directly
            dataOutput.writeObject(item);

            // Encode the byte array to a Base64 string (use lines version for better readability in YML potentially)
            return Base64Coder.encodeLines(outputStream.toByteArray());

        } catch (IOException e) {
            TKits.getInstance().getMessageUtil().logException("ItemStack Serialization IO Error", e);
            throw new IllegalStateException("Unable to serialize ItemStack to Base64.", e);
        } catch (Exception e) { // Catch other unexpected errors
            TKits.getInstance().getMessageUtil().logException("Unexpected ItemStack Serialization Error", e);
            throw new IllegalStateException("Unexpected error serializing ItemStack.", e);
        }
    }

    /**
     * Deserializes a Base64 encoded string back into an ItemStack.
     *
     * @param data The Base64 encoded string data. Can be null or empty.
     * @return The deserialized ItemStack, or null if the input data was null/empty or deserialization failed.
     */
    public static ItemStack itemStackFromBase64(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {

            // Read the single ItemStack object
            Object readObject = dataInput.readObject();

            if (readObject instanceof ItemStack) {
                return (ItemStack) readObject;
            } else {
                TKits.getInstance().getLogger().warning("Deserialized object is not an ItemStack: " + (readObject != null ? readObject.getClass().getName() : "null"));
                return null;
            }

        } catch (ClassNotFoundException e) {
            // This often means the server version changed or Bukkit internals shifted
            TKits.getInstance().getLogger().warning("Could not find class during ItemStack deserialization (potentially due to version change or corrupt data). Data: " + data.substring(0, Math.min(50, data.length())) + "...");
            return null;
        } catch (IOException e) {
             TKits.getInstance().getMessageUtil().logException("ItemStack Deserialization IO Error", e);
            return null;
        } catch (Exception e) { // Catch ClassCastException or other runtime issues
            TKits.getInstance().getMessageUtil().logException("Unexpected ItemStack Deserialization Error", e);
             return null;
        }
    }

    // --- Helpers for Kitroom Config Handling ---
    // These methods interact with common ways items might be stored in kitroom.yml

    /**
     * Saves a map of items (slot -> ItemStack) to a Map structure suitable for saving in YAML
     * using the preferred map format (`slot_number_as_string: base64_item_data`).
     * Modifies the provided configMap directly.
     *
     * @param items     The map of items to save.
     * @param configMap The Map object (typically from a ConfigurationSection) to save into. Cleared before population.
     */
    public static void saveItemsToConfigMap(Map<Integer, ItemStack> items, Map<String, Object> configMap) {
        configMap.clear(); // Clear previous entries
        if (items == null || items.isEmpty()) {
            return;
        }
        items.forEach((slot, item) -> {
             // Skip null/AIR items, but allow saving in valid slots (0-44 for kitroom items)
            if (item != null && item.getType() != Material.AIR && slot >= 0 && slot < 45) {
                try {
                    String serialized = itemStackToBase64(item);
                    if (serialized != null) { // Check if serialization was successful
                         configMap.put(String.valueOf(slot), serialized);
                     }
                 } catch (IllegalStateException e) {
                     TKits.getInstance().getMessageUtil().logSevere("Failed to serialize item (Slot " + slot + ") for kitroom config map: " + item.getType());
                      // Log the exception contained in 'e' if needed for more detail
                 }
             } else if (item != null && item.getType() != Material.AIR) {
                 TKits.getInstance().getMessageUtil().logWarning("Attempted to save kitroom item to invalid slot " + slot + ". Item: " + item.getType());
             }
        });
    }

    /**
     * Loads items from a Map structure (typically read from a YAML ConfigurationSection)
     * where keys are slot numbers as strings and values are Base64 encoded ItemStacks.
     *
     * @param configMap The Map containing the serialized item data (e.g., `section.getValues(false)`).
     * @return A Map of Integer (slot) -> ItemStack. Returns an empty map if input is null or loading fails.
     */
    public static Map<Integer, ItemStack> loadItemsFromConfigMap(Map<String, Object> configMap) {
        Map<Integer, ItemStack> items = new HashMap<>();
        if (configMap == null || configMap.isEmpty()) {
            return items;
        }

        configMap.forEach((key, value) -> {
            try {
                int slot = Integer.parseInt(key);
                if (value instanceof String) {
                    ItemStack item = itemStackFromBase64((String) value);
                    if (item != null && item.getType() != Material.AIR) { // Ensure loaded item is valid
                         // Only load into valid kitroom item slots
                         if (slot >= 0 && slot < 45) {
                             items.put(slot, item);
                         } else {
                              TKits.getInstance().getMessageUtil().logWarning("Kitroom item loaded from config for invalid slot " + slot + ". Ignoring.");
                         }
                    }
                } else {
                    TKits.getInstance().getMessageUtil().logWarning("Invalid value type for kitroom item key '" + key + "' in config. Expected String, found " + (value != null ? value.getClass().getSimpleName() : "null"));
                }
            } catch (NumberFormatException e) {
                 TKits.getInstance().getMessageUtil().logWarning("Invalid slot number format '" + key + "' found in kitroom config map. Skipping.");
            } catch (Exception e) { // Catch potential errors from itemStackFromBase64
                 TKits.getInstance().getMessageUtil().logException("Failed to deserialize kitroom item from config map entry: " + key, e);
            }
        });
        return items;
    }
}