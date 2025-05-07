package com.takeda.tkits.models;

import com.takeda.tkits.TKits; 
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

    
    private Map<Integer, ItemStack> items = new HashMap<>();

    public ItemStack[] getBukkitInventoryContents() {
        ItemStack[] contents = new ItemStack[41];
        items.forEach((slot, item) -> {
            if (slot >= 0 && slot < contents.length && item != null) {
                contents[slot] = item.clone(); 
            }
        });
        return contents;
    }

    public void setFromBukkitContents(ItemStack[] contents) {
        items.clear();
        if (contents != null) {
            for (int i = 0; i < contents.length; i++) {
                 if (i > 40) continue; 
                if (contents[i] != null && contents[i].getType() != Material.AIR) {
                    items.put(i, contents[i].clone()); 
                }
            }
        }
    }

    
    public static String serialize(KitContents contents) {
         if (contents == null || contents.items.isEmpty()) {
             return null; 
         }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {

            dataOutput.writeInt(contents.items.size()); 
            for (Map.Entry<Integer, ItemStack> entry : contents.items.entrySet()) {
                dataOutput.writeInt(entry.getKey()); 
                dataOutput.writeObject(entry.getValue()); 
            }
            return Base64Coder.encodeLines(outputStream.toByteArray());

        } catch (IOException e) {
            
             TKits.getInstance().getMessageUtil().logException("Serialization Error for KitContents", e);
            throw new IllegalStateException("Unable to serialize KitContents", e); 
        } catch (Exception e) { 
            TKits.getInstance().getMessageUtil().logException("Unexpected Serialization Error for KitContents", e);
            throw new IllegalStateException("Unexpected error serializing KitContents", e);
        }
    }

    
    public static KitContents deserialize(String base64) {
         if (base64 == null || base64.isEmpty()) {
             return new KitContents(); 
         }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(base64));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {

            int size = dataInput.readInt();
            Map<Integer, ItemStack> items = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                 int slot = dataInput.readInt();
                 try {
                     
                     ItemStack item = (ItemStack) dataInput.readObject();
                      if (item != null && item.getType() != Material.AIR) { 
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
            
             return new KitContents();
        } catch (Exception e) { 
            TKits.getInstance().getMessageUtil().logException("Unexpected Deserialization Error for KitContents", e);
            return new KitContents();
        }
    }
}