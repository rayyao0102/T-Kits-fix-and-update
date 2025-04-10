package com.takeda.tkits.listeners;

import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.services.CooldownService;
import com.takeda.tkits.services.UtilityService;
import com.takeda.tkits.util.GuiUtils;
import com.takeda.tkits.util.MessageUtil;
import java.util.List; // Keep List import
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter; // Added Lombok Getter annotation for holder
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder; // Import InventoryHolder
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue; // Import MetadataValue
import org.bukkit.persistence.PersistentDataType;

public class InteractionListener implements Listener {

    private final TKits plugin;
    private final MessageUtil msg;
    private final UtilityService utilityService;
    private final CooldownService cooldownService;
    private final NamespacedKey regearBoxKey;
    private final NamespacedKey regearShellKey;
    private final Map<Location, UUID> placedRegearBoxes =
        new ConcurrentHashMap<>();

    public InteractionListener(TKits plugin) {
        this.plugin = plugin;
        this.msg = plugin.getMessageUtil();
        this.utilityService = plugin.getUtilityService();
        this.cooldownService = plugin.getCooldownService();
        this.regearBoxKey = new NamespacedKey(plugin, "regear_box");
        this.regearShellKey = new NamespacedKey(plugin, "regear_shell");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRegearBoxPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack itemInHand = event.getItemInHand();
        Block blockPlaced = event.getBlockPlaced();

        if (
            !blockPlaced.getType().name().contains("SHULKER_BOX") ||
            !itemInHand.hasItemMeta()
        ) return;

        ItemMeta meta = itemInHand.getItemMeta();
        if (
            meta != null &&
            meta
                .getPersistentDataContainer()
                .has(regearBoxKey, PersistentDataType.BYTE)
        ) {
            if (
                plugin.getCombatTagManager().isTagged(player) &&
                !player.hasPermission("tkits.admin")
            ) {
                long remaining = plugin
                    .getCombatTagManager()
                    .getRemainingTagTimeMillis(player);
                msg.sendMessage(
                    player,
                    "in_combat",
                    "time",
                    String.format("%.1f", remaining / 1000.0)
                );
                msg.playSound(player, "error");
                event.setCancelled(true);
                return;
            }

            Location loc = blockPlaced.getLocation();
            // Optional: Check if player already has a box placed? Limit to one?
            // if (findPlacedRegearBox(player.getUniqueId()) != null) { ... }

            placedRegearBoxes.put(loc, player.getUniqueId());
            blockPlaced.setMetadata(
                "tkits_regear_owner",
                new FixedMetadataValue(plugin, player.getUniqueId().toString())
            );

            // Use action bar for placement confirmation
            msg.sendActionBar(player, "regear_shulker_given"); // Re-use existing message key
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegearBoxOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clickedBlock = event.getClickedBlock();
        if (
            clickedBlock == null ||
            !clickedBlock.getType().name().contains("SHULKER_BOX")
        ) return;

        // Check metadata *first* before map, as map might be cleared but metadata remains temporarily
        String ownerUUIDStr = null;
        List<MetadataValue> metaValues = clickedBlock.getMetadata(
            "tkits_regear_owner"
        );
        if (!metaValues.isEmpty()) {
            ownerUUIDStr = metaValues.get(0).asString();
        }

        // If metadata exists, this is likely our box, proceed with checks
        if (ownerUUIDStr != null) {
            event.setCancelled(true); // Prevent default open only if it's confirmed our box

            Player player = event.getPlayer();
            Location loc = clickedBlock.getLocation();

            try {
                UUID ownerUUID = UUID.fromString(ownerUUIDStr);
                if (!player.getUniqueId().equals(ownerUUID)) {
                    msg.sendMessage(player, "not_your_regear_box");
                    msg.playSound(player, "error");
                    return; // Don't proceed
                }
                // Verify it's still tracked in our map for extra safety?
                // if (!placedRegearBoxes.containsKey(loc) || !placedRegearBoxes.get(loc).equals(ownerUUID)) { ... }

            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                msg.logWarning(
                    "Failed to parse owner UUID metadata from placed regear box at " +
                    loc
                );
                removeBox(loc); // Clean up box with bad state
                return;
            }

            if (
                plugin.getCombatTagManager().isTagged(player) &&
                !player.hasPermission("tkits.admin")
            ) {
                long remaining = plugin
                    .getCombatTagManager()
                    .getRemainingTagTimeMillis(player);
                msg.sendMessage(
                    player,
                    "in_combat",
                    "time",
                    String.format("%.1f", remaining / 1000.0)
                );
                msg.playSound(player, "error");
                return;
            }

            Kit lastKit = plugin.getKitManager().getLastLoadedKit(player);
            if (lastKit == null) {
                msg.playSound(player, "error"); // Message already sent by kit manager
                removeBox(loc);
                return;
            }

            openRegearShellInventory(player, lastKit);
        }
        // If no metadata, it's a normal shulker, do nothing, allow default action.
    }

    private void openRegearShellInventory(Player player, Kit lastKit) {
        Component title = msg.deserialize(
            plugin
                .getConfigManager()
                .getMainConfig()
                .getString(
                    "gui.regear_box_title",
                    "&8Regear - Kit {kit_number}"
                )
                .replace("{kit_number}", String.valueOf(lastKit.getKitNumber()))
        );
        Inventory gui = Bukkit.createInventory(
            new RegearInventoryHolder(lastKit),
            InventoryType.SHULKER_BOX,
            title
        );

        // Create the regear button
        ItemStack shell = GuiUtils.createGuiItem(
            Material.SHULKER_SHELL,
            msg.deserialize("&a<0xF0><0x9F><0xAA><0xA6> Rᴇɢᴇᴀʀ Nᴏᴡ"),
            List.of(
                msg.deserialize("&7Click to restock your inventory"),
                msg.deserialize("&7using Kit " + lastKit.getKitNumber() + ".")
            ),
            0
        );
        ItemMeta shellMeta = shell.getItemMeta();
        if (shellMeta != null) {
            shellMeta
                .getPersistentDataContainer()
                .set(regearShellKey, PersistentDataType.BYTE, (byte) 1);
            shell.setItemMeta(shellMeta);
        }

        // Use slots 11-17 as originally intended
        for (int slot = 11; slot <= 17; slot++) {
            gui.setItem(slot, shell.clone());
        }

        player.openInventory(gui);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegearGuiClose(InventoryCloseEvent event) {
        if (
            event.getInventory().getHolder() instanceof RegearInventoryHolder &&
            event.getPlayer() instanceof Player
        ) {
            Player player = (Player) event.getPlayer();
            // Schedule removal to prevent conflicts if GUI is reopened immediately etc.
            Bukkit.getScheduler()
                .runTaskLater(
                    plugin,
                    () -> {
                        Location boxLoc = findPlacedRegearBox(
                            player.getUniqueId()
                        );
                        if (boxLoc != null) {
                            removeBox(boxLoc);
                        }
                    },
                    1L
                ); // Delay by 1 tick
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();

        // Check both map and metadata
        boolean isOurBox =
            placedRegearBoxes.containsKey(loc) ||
            block.hasMetadata("tkits_regear_owner");

        if (isOurBox) {
            // Prevent players breaking it unless admin?
            if (!event.getPlayer().hasPermission("tkits.admin")) {
                event.setCancelled(true);
                // Use raw message here as prefix might be redundant
                msg.sendRawMessage(
                    event.getPlayer(),
                    "&cCannot break active Regear Boxes.",
                    null
                );
                msg.playSound(event.getPlayer(), "error");
            } else { // Admin is breaking it
                event.setDropItems(false); // Prevent dropping the custom item
                removeBox(loc); // Clean up state immediately
                msg.sendRawMessage(
                    event.getPlayer(),
                    "&aAdmin: Removed placed Regear Box.",
                    null
                );
            }
        }
    }

    private void removeBox(Location loc) {
        if (loc == null) return;
        UUID ownerUUID = placedRegearBoxes.remove(loc); // Remove from tracking map first
        Block block = loc.getBlock();

        if (block.getType().name().contains("SHULKER_BOX")) { // Check block type again before changing
            // Clean up metadata FIRST, before setting type to AIR
            block.removeMetadata("tkits_regear_owner", plugin);
            block.removeMetadata("tkits_no_drop", plugin);
            block.setType(Material.AIR, true); // Set to air, apply physics
        }
        if (ownerUUID != null) plugin
            .getLogger()
            .fine(
                "Removed regear box placed by " +
                ownerUUID +
                " at " +
                loc.toVector()
            );
        // Optional sound/effect at location
    }

    private Location findPlacedRegearBox(UUID playerUUID) {
        // Iterate map to find the box location for this specific player
        return placedRegearBoxes
            .entrySet()
            .stream()
            .filter(entry -> playerUUID.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    // Custom Inventory Holder for the Regear GUI
    private static class RegearInventoryHolder implements InventoryHolder {

        @Getter
        private final Kit lastKit;

        public RegearInventoryHolder(Kit lastKit) {
            this.lastKit = lastKit;
        }

        @Override
        public Inventory getInventory() {
            return null;
        } // Temporary holder
    }
}
