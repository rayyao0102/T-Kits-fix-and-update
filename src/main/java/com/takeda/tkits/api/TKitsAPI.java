package com.takeda.tkits.api;

import com.takeda.tkits.models.Kit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for interacting with T-Kits functionality.
 * Obtain an instance via Bukkit Services Manager:
 * <pre>{@code
 * RegisteredServiceProvider<TKitsAPI> provider = Bukkit.getServicesManager().getRegistration(TKitsAPI.class);
 * if (provider != null) {
 *     TKitsAPI api = provider.getProvider();
 *     // Use the API
 * }
 * }</pre>
 */
public interface TKitsAPI {

    /**
     * Gets a player's specific kit, if it exists.
     * <p>
     * Note: Accesses cached data for online players. For offline players, this performs
     * a <b>synchronous</b> storage lookup which can be slow and potentially block the main thread.
     * It is highly recommended to use {@link #getPlayerKitAsync(UUID, int)} for offline players or
     * from asynchronous contexts.
     *
     * @param playerUUID The UUID of the player.
     * @param kitNumber The kit number (typically 1-7).
     * @return An Optional containing the Kit if found, otherwise empty. Returns empty if lookup fails.
     */
    Optional<Kit> getPlayerKit(UUID playerUUID, int kitNumber);

    /**
     * Asynchronously gets a player's specific kit from storage. This is the recommended method
     * for accessing kit data, especially for offline players or outside the main server thread.
     *
     * @param playerUUID The UUID of the player.
     * @param kitNumber The kit number (typically 1-7).
     * @return A CompletableFuture that will complete with an Optional containing the Kit if found.
     *         The future may complete exceptionally if a storage error occurs.
     */
    CompletableFuture<Optional<Kit>> getPlayerKitAsync(UUID playerUUID, int kitNumber);


     /**
      * Gets all kits belonging to a player.
      * <p>
      * Note: Accesses cached data for online players. For offline players, this performs
      * a <b>synchronous</b> storage lookup. Prefer {@link #getAllPlayerKitsAsync(UUID)} for
      * asynchronous or offline player access.
      *
      * @param playerUUID The UUID of the player.
      * @return An unmodifiable Map of Kit Number -> Kit, potentially empty. Returns empty if lookup fails.
      */
     Map<Integer, Kit> getAllPlayerKits(UUID playerUUID);

      /**
      * Asynchronously gets all kits belonging to a player from storage.
      *
      * @param playerUUID The UUID of the player.
      * @return A CompletableFuture that will complete with a Map of Kit Number -> Kit.
      *         The map will be empty if the player has no kits or an error occurs.
      *         The future may complete exceptionally on storage errors.
      */
     CompletableFuture<Map<Integer, Kit>> getAllPlayerKitsAsync(UUID playerUUID);


     /**
      * Loads a specific kit onto a player, replacing their inventory, armor, offhand,
      * and ender chest contents, and clearing potion effects. Also resets basic stats
      * like health, food, and experience. Checks for cooldowns unless the player
      * has the bypass permission.
      * <p>
      * This operation should be performed on the main server thread.
      *
      * @param player The player to apply the kit to.
      * @param kitNumber The kit number to load.
      * @return True if the kit was found and loaded successfully (considering cooldowns), false otherwise.
      */
     boolean loadKitOntoPlayer(Player player, int kitNumber);

     /**
      * Gets the kit number the player loaded most recently in their current session.
      * Returns -1 if no kit has been loaded or player data isn't available.
      * This relies on the in-memory cache for the player.
      *
      * @param player The player.
      * @return The last loaded kit number, or -1.
      */
     int getLastLoadedKitNumber(Player player);


      /**
      * Gets a list of all currently cached global kits. The list is sorted for consistency.
      * This reflects kits marked as global in the plugin's current state.
      *
      * @return An unmodifiable list of Kits marked as global.
      */
     List<Kit> getGlobalKits();

     /**
      * Checks if a player is currently considered in combat according to T-Kits's
      * combat tag system (either internal or via a potential external hook).
      *
      * @param player The player to check.
      * @return True if the player is considered in combat, false otherwise or if tagging is disabled.
      */
     boolean isPlayerCombatTagged(Player player);

      /**
       * Gets the remaining combat tag duration for a player in milliseconds.
       * Returns 0 if the player is not tagged or tagging is disabled.
       *
       * @param player The player to check.
       * @return Remaining duration in milliseconds.
       */
      long getRemainingCombatTagMillis(Player player);

       /**
        * Creates the specific "Regear Box" item stack used by the /regear command.
        * This item, when placed and right-clicked by its placer, triggers the regear process.
        *
        * @return The ItemStack representing the Regear Box trigger item. Returns an empty item if creation fails.
        */
       ItemStack getRegearTriggerItem();

        /**
         * Triggers the regear process for a player using their last loaded kit.
         * This bypasses the need for the regear shulker box item.
         * Checks for cooldowns unless the player has bypass permission.
         *
         * @param player The player to regear.
         * @return True if the regear process was initiated successfully, false if no kit was loaded, player is on cooldown, or an error occurred.
         */
        boolean performRegear(Player player);

        /**
         * Triggers the inventory arrange process for a player using their last loaded kit layout.
         * Checks for cooldowns unless the player has bypass permission.
         *
         * @param player The player whose inventory to arrange.
         * @return True if the arrange process was completed successfully, false if no kit was loaded, player is on cooldown, or an error occurred.
         */
        boolean performArrange(Player player);

}