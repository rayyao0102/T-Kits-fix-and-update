package com.takeda.tkits.managers;

import com.takeda.tkits.TKits;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public class KitroomManager {

    private final TKits plugin;
    
    private final Map<String, Map<Integer, ItemStack>> categories = new ConcurrentHashMap<>();
    
    public static final List<String> CATEGORY_ORDER = List.of("armor", "sword", "utilities", "potions", "arrows", "others");


    public KitroomManager(TKits plugin) {
        this.plugin = plugin;
        loadKitroom();
    }

    public synchronized void loadKitroom() {
        categories.clear();
        FileConfiguration config = plugin.getConfigManager().getKitroomConfig();
        ConfigurationSection categoriesSection = config.getConfigurationSection("categories");
        if (categoriesSection == null) {
            plugin.getLogger().info("Kitroom config missing 'categories' section. Generating default kitroom.");
            generateDefaultKitroom(); 
            saveKitroom(); 
            return;
        }

        for (String categoryKey : categoriesSection.getKeys(false)) {
            
            ConfigurationSection categorySec = categoriesSection.getConfigurationSection(categoryKey);
            if (categorySec == null) continue;

            Map<Integer, ItemStack> slotMap = new HashMap<>();
            for (String slotKey : categorySec.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(slotKey);
                    
                    ItemStack item = categorySec.getItemStack(slotKey); 
                    if (item != null && slot >= 0 && slot < 45) { 
                        slotMap.put(slot, item);
                    } else if (item != null) {
                         plugin.getLogger().warning("Kitroom item loaded from config for invalid slot " + slot + " in category '" + categoryKey + "'. Ignoring.");
                    }
                } catch (NumberFormatException e) {
                     plugin.getLogger().warning("Invalid slot number format '" + slotKey + "' in kitroom category '" + categoryKey + "'. Skipping.");
                } catch (Exception e) {
                     plugin.getMessageUtil().logException("Failed to load item for slot " + slotKey + " in category " + categoryKey, e);
                }
            }
            categories.put(categoryKey.toLowerCase(), slotMap); 
        }

        
        for (String defaultCategory : CATEGORY_ORDER) {
            categories.computeIfAbsent(defaultCategory, k -> new HashMap<>());
        }

        if (categories.isEmpty()) {
            plugin.getLogger().info("Kitroom categories empty after load. Generating default kitroom.");
            generateDefaultKitroom();
            saveKitroom(); 
        } 
    }

    public synchronized void saveKitroom() {
        FileConfiguration config = plugin.getConfigManager().getInternalKitroomConfigForEdit();
        config.set("items", null); 
        config.set("categories", null); 
        for (Map.Entry<String, Map<Integer, ItemStack>> categoryEntry : categories.entrySet()) {
            String categoryKey = categoryEntry.getKey();
            Map<Integer, ItemStack> slotMap = categoryEntry.getValue();
            
            if (slotMap.isEmpty() && !CATEGORY_ORDER.contains(categoryKey)) {
                 continue;
            }
            for (Map.Entry<Integer, ItemStack> slotEntry : slotMap.entrySet()) {
                String slotKey = String.valueOf(slotEntry.getKey());
                ItemStack item = slotEntry.getValue();
                
                if (item != null && item.getType() != org.bukkit.Material.AIR) {
                    config.set("categories." + categoryKey + "." + slotKey, item);
                }
            }
            
            if (slotMap.isEmpty() && CATEGORY_ORDER.contains(categoryKey)) {
                 config.createSection("categories." + categoryKey);
            }
        }
        plugin.getConfigManager().saveKitroomConfig();
    }

    public synchronized void generateDefaultKitroom() {
        categories.clear();
        
        for (String category : CATEGORY_ORDER) {
            categories.put(category, new HashMap<>());
        }
        plugin.getLogger().info("Generated default empty kitroom categories.");
    }

    
    public List<String> getCategoryNames() {
        
        List<String> sortedNames = new ArrayList<>(CATEGORY_ORDER);
        categories.keySet().stream()
            .filter(cat -> !CATEGORY_ORDER.contains(cat))
            .sorted()
            .forEach(sortedNames::add);
        return sortedNames;
    }

    public Map<Integer, ItemStack> getCategoryItems(String categoryName) {
        return categories.getOrDefault(categoryName.toLowerCase(), new HashMap<>());
    }

    public void setCategoryItems(String categoryName, Map<Integer, ItemStack> items) {
        categories.put(categoryName.toLowerCase(), new HashMap<>(items)); 
    }

    public void addItemToCategory(String categoryName, int slot, ItemStack item) {
        categories.computeIfAbsent(categoryName.toLowerCase(), k -> new HashMap<>()).put(slot, item);
    }

    public void removeItemFromCategory(String categoryName, int slot) {
        Map<Integer, ItemStack> items = categories.get(categoryName.toLowerCase());
        if (items != null) {
            items.remove(slot);
        }
    }
}
