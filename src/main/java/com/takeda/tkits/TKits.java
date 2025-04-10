package com.takeda.tkits;

import co.aikar.commands.PaperCommandManager;
import com.takeda.tkits.api.TKitsAPI;
import com.takeda.tkits.api.TKitsAPIImpl;
import com.takeda.tkits.commands.*;
import com.takeda.tkits.config.ConfigManager;
import com.takeda.tkits.listeners.GuiListener;
import com.takeda.tkits.listeners.InteractionListener;
import com.takeda.tkits.listeners.PlayerListener;
import com.takeda.tkits.managers.*;
import com.takeda.tkits.models.PlayerData;
import com.takeda.tkits.placeholders.TKitsPlaceholderExpansion;
import com.takeda.tkits.services.CooldownService;
import com.takeda.tkits.services.UtilityService;
import com.takeda.tkits.storage.MySQLStorageHandler;
import com.takeda.tkits.storage.StorageHandler;
import com.takeda.tkits.storage.YamlStorageHandler;
import com.takeda.tkits.util.MessageUtil;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

@Getter
public final class TKits extends JavaPlugin {

    @Getter private static TKits instance;

    private ConfigManager configManager;
    private MessageUtil messageUtil;
    private StorageHandler storageHandler;
    private KitManager kitManager;
    private KitroomManager kitroomManager;
    private PlayerDataManager playerDataManager;
    private CombatTagManager combatTagManager;
    private ShareCodeManager shareCodeManager;
    private GuiManager guiManager;
    private CooldownService cooldownService;
    private UtilityService utilityService;
    private TKitsAPI api;

    private PaperCommandManager commandManager;

    @Override
    public void onEnable() {
        instance = this;
        this.getLogger().info("§6  _  __ _  _        ");
        this.getLogger().info("§6 | |/ /(_)| |       ");
        this.getLogger().info("§6 | ' /  _ | |_  ___ ");
        this.getLogger().info("§6 |  <  | || __|/ __|");
        this.getLogger().info("§6 | . \\ | || |_ \\__ \\");
        this.getLogger().info("§6 |_|\\_\\|_| \\__||___/");
        this.getLogger().info("Initializing T-Kits v" + getDescription().getVersion() + "...");

        if (!setupPlaceholderAPI()) {
             this.getLogger().warning("PlaceholderAPI not found! Placeholders using %tkits_...% will not work.");
        }

        loadConfiguration();
        setupUtilities();
        setupStorage();
        setupManagersAndServices();
        setupAPI();
        registerCommands();
        registerListeners();
        registerPlaceholders();


        this.getLogger().info("T-Kits has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (playerDataManager != null) {
            playerDataManager.saveAllPlayerDataBlocking(); // Use blocking save on shutdown
        }

        if (storageHandler != null) {
            storageHandler.shutdown();
        }

         if(commandManager != null) {
             commandManager.unregisterCommands(); // Clean up commands
         }

        this.getLogger().info("T-Kits has been disabled.");
        instance = null;
    }

    private void loadConfiguration() {
        // Ensure ConfigManager instance exists
        if (this.configManager == null) {
             this.configManager = new ConfigManager(this); // Initialize if it's null (should only happen on first load)
             // Load initial configs (setupFiles is called in constructor)
             // ConfigManager constructor now calls loadConfigs itself
        } else {
            // If ConfigManager already exists (e.g., during reload), tell it to reload all files
            this.configManager.loadConfigs();
        }
    }

    private void setupUtilities() {
        this.messageUtil = new MessageUtil(this);
    }

    private void setupStorage() {
        String storageType = configManager.getMainConfig().getString("storage.type", "YAML").toUpperCase();
        try {
            switch (storageType) {
                case "MYSQL":
                    this.storageHandler = new MySQLStorageHandler(this);
                    break;
                case "YAML":
                default:
                     storageType = "YAML"; // Ensure correct name for logging if default used
                    this.storageHandler = new YamlStorageHandler(this);
                    break;
            }
            this.getLogger().info("Initializing " + storageType + " storage handler...");
            storageHandler.init();
            this.getLogger().info(storageType + " storage handler initialized successfully.");
        } catch (Exception e) {
            this.getLogger().log(Level.SEVERE, "Failed to initialize primary storage handler (" + storageType + ")! Falling back to YAML.", e);
            this.storageHandler = new YamlStorageHandler(this);
            try {
                storageHandler.init();
                 this.getLogger().info("Successfully initialized YAML storage handler as fallback.");
            } catch (Exception ex) {
                 this.getLogger().log(Level.SEVERE, "FATAL: Failed to initialize YAML storage handler after fallback! Disabling plugin.", ex);
                 Bukkit.getPluginManager().disablePlugin(this);
            }
        }
    }

     private void setupManagersAndServices() {
        this.playerDataManager = new PlayerDataManager(this);
        // Initialize services needed by managers first
        this.cooldownService = new CooldownService(this);
        this.utilityService = new UtilityService(this); // Initialize UtilityService

        // Initialize managers that might depend on services
        this.kitManager = new KitManager(this);
        this.kitroomManager = new KitroomManager(this);
        this.combatTagManager = new CombatTagManager(this);
        this.shareCodeManager = new ShareCodeManager(this);
        this.guiManager = new GuiManager(this); // GuiManager depends on others

        kitManager.loadGlobalKitsFromStorage();
     }

    public KitroomManager getKitroomManager() {
        return kitroomManager;
    }

    private void setupAPI() {
        this.api = new TKitsAPIImpl(this);
        Bukkit.getServicesManager().register(TKitsAPI.class, this.api, this, ServicePriority.Normal);
        this.getLogger().info("T-Kits API registered.");
    }

    private void registerCommands() {
        commandManager = new PaperCommandManager(this);

        // Set messages provider (optional but good for consistency)
        commandManager.getLocales().addMessageBundles("tkits-lang"); // Assumes a language file if needed, basic approach here uses MessageUtil
        commandManager.getLocales().setDefaultLocale(java.util.Locale.ENGLISH);

         // Configure ACF Completions & Contexts
         commandManager.getCommandCompletions().registerAsyncCompletion("tkits_kits", c -> {
             Player player = c.getPlayer();
             if (player == null) return List.of("1","2","3","4","5","6","7");
             PlayerData data = this.getPlayerDataManager().getPlayerData(player);
             if (data != null) {
                return data.getKits().keySet().stream().map(String::valueOf).sorted().collect(Collectors.toList());
             }
             return List.of("1","2","3","4","5","6","7");
         });
          commandManager.getCommandCompletions().registerAsyncCompletion("tkits_own_kits", c -> { // Only show existing kits owned by player
             Player player = c.getPlayer();
             if (player == null) return List.of();
             PlayerData data = this.getPlayerDataManager().getPlayerData(player);
             if (data != null) {
                return data.getKits().keySet().stream().map(String::valueOf).sorted().collect(Collectors.toList());
             }
             return List.of();
         });

        commandManager.getCommandCompletions().registerCompletion("storage_types", c -> List.of("YAML", "MYSQL"));
        commandManager.getCommandCompletions().registerCompletion("confirm", c -> List.of("confirm"));


        // Register command classes
        commandManager.registerCommand(new KitCommand(this));
        commandManager.registerCommand(new DirectKitLoadCommands(this));
        commandManager.registerCommand(new UtilityCommands(this));
        commandManager.registerCommand(new AdminCommand(this));

        this.getLogger().info("Commands registered.");
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
        Bukkit.getPluginManager().registerEvents(new InteractionListener(this), this);
         if (combatTagManager.isInternalCombatTagEnabled()) { // Only register combat tag listener if using internal system
            Bukkit.getPluginManager().registerEvents(combatTagManager, this);
         }
        this.getLogger().info("Listeners registered.");
    }

     private boolean setupPlaceholderAPI() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    private void registerPlaceholders() {
        if (setupPlaceholderAPI()) { // Use the check method
           new TKitsPlaceholderExpansion(this).register();
           this.getLogger().info("PlaceholderAPI expansion registered.");
        } else {
            // Warning already logged in onEnable
        }
    }

    public void reloadPlugin() {
         this.getLogger().info("Reloading T-Kits...");
         long startTime = System.currentTimeMillis();

         // Save any pending data
         playerDataManager.saveAllPlayerData().join(); // Wait for async saves during reload? Or just fire-and-forget? Waiting might be safer.

         // Shutdown existing resources (Careful: reloading HikariCP is complex)
          // Best practice often avoids fully reloading database connections unless necessary.
          // Just reload config files here. Full reload might require /restart.

         // 1. Reload Configuration
         loadConfiguration(); // This will now call configManager.loadConfigs()

         // 2. Re-read config settings for managers/services
          combatTagManager.reloadConfigSettings(); // Add method to CombatTagManager to update duration etc.
          cooldownService.loadCooldowns(); // Reload cooldown durations
          messageUtil.reloadMessages(); // Reload messages from config
          messageUtil.reloadPrefix(); // Reload prefix from config
          shareCodeManager.reloadConfigSettings(); // Add method
         // Reload GUI Manager settings if necessary? (Likely reads directly from config on use)
         guiManager.reloadGuiConfig(); // Add this method to reload titles/item defs


          // 4. Reregister commands? ACF might handle updates, or may need unregister/register
          // Let's assume config changes affecting commands are rare, skip re-register for now.

          long duration = System.currentTimeMillis() - startTime;
         this.getLogger().info("T-Kits reloaded successfully in " + duration + "ms.");
         messageUtil.logInfo("Plugin configuration reloaded."); // Log to console via MessageUtil too
    }
}
