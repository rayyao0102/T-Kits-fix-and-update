package com.takeda.tkits.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionType;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Simple ItemBuilder utility class.
 * Note: Consider using a dedicated library like ItemNBTAPI for complex NBT manipulation if needed.
 */
public class ItemBuilder {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand().toBuilder().hexColors().build();
    private final ItemStack itemStack;
    private ItemMeta itemMeta;

    public ItemBuilder(Material material) {
        this(material, 1);
    }

    public ItemBuilder(Material material, int amount) {
        this.itemStack = new ItemStack(material, amount);
        this.itemMeta = this.itemStack.getItemMeta(); // Get meta right away
    }

    public ItemBuilder(ItemStack itemStack) {
        this.itemStack = itemStack.clone(); // Work on a clone
        this.itemMeta = this.itemStack.getItemMeta();
    }

    public ItemBuilder amount(int amount) {
        this.itemStack.setAmount(Math.max(1, amount));
        return this;
    }

    public ItemBuilder name(String legacyName) {
        return name(LEGACY_SERIALIZER.deserialize(legacyName));
    }

    public ItemBuilder name(Component name) {
        if (this.itemMeta != null) {
             // Default to no italics for builder names unless specified in component
            this.itemMeta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return this;
    }

    public ItemBuilder lore(List<String> legacyLore) {
        if (legacyLore == null || legacyLore.isEmpty()) {
             if (this.itemMeta != null) this.itemMeta.lore(null);
            return this;
        }
        return loreComponents(legacyLore.stream()
            .map(LEGACY_SERIALIZER::deserialize)
            .collect(Collectors.toList()));
    }

     public ItemBuilder lore(String... legacyLore) {
         return lore(Arrays.asList(legacyLore));
     }

    public ItemBuilder loreComponents(List<Component> lore) {
        if (this.itemMeta != null) {
             this.itemMeta.lore(lore == null ? null : lore.stream()
                .map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)) // No italics default
                .collect(Collectors.toList()));
        }
        return this;
    }

     public ItemBuilder loreComponents(Component... lore) {
         return loreComponents(Arrays.asList(lore));
     }

    public ItemBuilder enchant(Enchantment enchantment, int level) {
        if (this.itemMeta != null) {
            this.itemMeta.addEnchant(enchantment, level, true); // Ignore level restriction true
        }
        return this;
    }

    public ItemBuilder removeEnchant(Enchantment enchantment) {
         if (this.itemMeta != null) {
             this.itemMeta.removeEnchant(enchantment);
         }
         return this;
    }

    public ItemBuilder removeEnchants() {
         if (this.itemMeta != null) {
             this.itemMeta.getEnchants().keySet().forEach(this.itemMeta::removeEnchant);
         }
        return this;
    }

    public ItemBuilder unbreakable(boolean unbreakable) {
        if (this.itemMeta != null) {
            this.itemMeta.setUnbreakable(unbreakable);
        }
        return this;
    }

    public ItemBuilder modelData(int customModelData) {
        if (this.itemMeta != null) {
            this.itemMeta.setCustomModelData(customModelData > 0 ? customModelData : null); // Set to null if 0 or less
        }
        return this;
    }

    public ItemBuilder itemFlags(ItemFlag... flags) {
        if (this.itemMeta != null) {
            this.itemMeta.addItemFlags(flags);
        }
        return this;
    }

    public ItemBuilder removeItemFlags(ItemFlag... flags) {
        if (this.itemMeta != null) {
            this.itemMeta.removeItemFlags(flags);
        }
        return this;
    }

    public ItemBuilder skullOwner(String ownerName) {
         if (this.itemMeta instanceof SkullMeta) {
             try {
                  // Note: setOwner is deprecated, use setOwningPlayer with OfflinePlayer object for better results
                  // SkullMeta skullMeta = (SkullMeta) this.itemMeta;
                  // skullMeta.setOwner(ownerName); // Deprecated method
             } catch (Exception ignored) { /* Handle potential errors */ }
         }
         return this;
     }

      public ItemBuilder potion(PotionType type) {
          if (this.itemMeta instanceof PotionMeta) {
              ((PotionMeta) this.itemMeta).setBasePotionType(type);
          }
          return this;
      }

      public ItemBuilder potionColor(Color color) {
          if (this.itemMeta instanceof PotionMeta) {
              ((PotionMeta) this.itemMeta).setColor(color);
          }
           else if (this.itemMeta instanceof LeatherArmorMeta) {
              ((LeatherArmorMeta) this.itemMeta).setColor(color);
           }
          return this;
      }


    public ItemStack build() {
         if (this.itemMeta != null) {
            this.itemStack.setItemMeta(this.itemMeta);
        }
        return this.itemStack;
    }
}