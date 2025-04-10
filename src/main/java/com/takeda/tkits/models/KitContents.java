package com.takeda.tkits.models;

import com.takeda.tkits.TKits; // Needed for logging
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KitContents {

    // Slot -> ItemStack mapping (0-35 inv, 36 boots, 37 legs, 38 chest, 39 helm, 40 offhand)
    private Map<Integer, ItemStack> items = new HashMap<>();

    public ItemStack[] getBukkitInventoryContents() {
        ItemStack[] contents = new ItemStack[41];
        items.forEach((slot, item) -> {
            if (slot >= 0 && slot < contents.length && item != null) {
                contents[slot] = item.clone(); // Return clones
            }
        });
        return contents;
    }

    public void setFromBukkitContents(ItemStack[] contents) {
        items.clear();
        if (contents != null) {
            for (int i = 0; i < contents.length; i++) {
                 if (i > 40) continue; // Ignore slots beyond offhand (e.g., from creative?)
                if (contents[i] != null && contents[i].getType() != Material.AIR) {
                    items.put(i, contents[i].clone()); // Store clones
                }
            }
        }
    }

    // Static method for serialization using Base64 encoded Bukkit streams
    public static String serialize(KitContents contents) {
         if (contents == null || contents.items.isEmpty()) {
             return null; // Represent empty contents as null string for storage efficiency
         }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {

            dataOutput.writeInt(contents.items.size()); // Write map size first
            for (Map.Entry<Integer, ItemStack> entry : contents.items.entrySet()) {
                dataOutput.writeInt(entry.getKey()); // Write slot
                dataOutput.writeObject(entry.getValue()); // Write ItemStack object
            }
            return Base64Coder.encodeLines(outputStream.toByteArray());

        } catch (IOException e) {
            // Use plugin logger if available (static access or pass instance)
             TKits.getInstance().getMessageUtil().logException("Serialization Error for KitContents", e);
            throw new IllegalStateException("Unable to serialize KitContents", e); // Re-throw critical exception
        } catch (Exception e) { // Catch other potential exceptions during serialization
            TKits.getInstance().getMessageUtil().logException("Unexpected Serialization Error for KitContents", e);
            throw new IllegalStateException("Unexpected error serializing KitContents", e);
        }
    }

    // Static method for deserialization
    public static KitContents deserialize(String base64) {
         if (base64 == null || base64.isEmpty()) {
             return new KitContents(); // Return new empty contents if data is null/empty
         }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(base64));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {

            int size = dataInput.readInt();
            Map<Integer, ItemStack> items = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                 int slot = dataInput.readInt();
                 try {
                     // Handle potential Material/class changes during deserialization
                     ItemStack item = (ItemStack) dataInput.readObject();
                      if (item != null && item.getType() != Material.AIR) { // Extra safety check
                         items.put(slot, item);
                      } else {
                           TKits.getInstance().getLogger().finer("Deserialized null or AIR item for slot " + slot + ". Ignoring.");
                      }
                 } catch (ClassNotFoundException e) {
                      TKits.getInstance().getLogger().warning("Could not find class for item in slot " + slot + " during deserialization. Item skipped.");
                 } catch (IOException e) {
                      TKits.getInstance().getLogger().log(Level.WARNING, "IO exception reading item object for slot " + slot + ". Item skipped.", e);
                 } catch (ClassCastException e) {
                      TKits.getInstance().getLogger().warning("Deserialized object is not an ItemStack for slot " + slot + ". Item skipped.");
                 }
            }
            return new KitContents(items);

        } catch (IOException e) {
             TKits.getInstance().getMessageUtil().logException("Deserialization IO Error for KitContents", e);
            // Return empty contents on error to prevent cascade failure? Or throw?
             return new KitContents();
        } catch (Exception e) { // Catch other potential issues
            TKits.getInstance().getMessageUtil().logException("Unexpected Deserialization Error for KitContents", e);
            return new KitContents();
        }
    }
}