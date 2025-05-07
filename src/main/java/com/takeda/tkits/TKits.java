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
            playerDataManager.saveAllPlayerDataBlocking(); 
        }

        if (storageHandler != null) {
            storageHandler.shutdown();
        }

         if(commandManager != null) {
             commandManager.unregisterCommands(); 
         }

        this.getLogger().info("T-Kits has been disabled.");
        instance = null;
    }

    private void loadConfiguration() {
        
        if (this.configManager == null) {
             this.configManager = new ConfigManager(this); 
             
             
        } else {
            
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
                     storageType = "YAML"; 
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
        
        this.cooldownService = new CooldownService(this);
        this.utilityService = new UtilityService(this); 

        
        this.kitManager = new KitManager(this);
        this.kitroomManager = new KitroomManager(this);
        this.combatTagManager = new CombatTagManager(this);
        this.shareCodeManager = new ShareCodeManager(this);
        this.guiManager = new GuiManager(this); 

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

        
        commandManager.getLocales().addMessageBundles("tkits-lang"); 
        commandManager.getLocales().setDefaultLocale(java.util.Locale.ENGLISH);

         
         commandManager.getCommandCompletions().registerAsyncCompletion("tkits_kits", c -> {
             Player player = c.getPlayer();
             if (player == null) return List.of("1","2","3","4","5","6","7");
             PlayerData data = this.getPlayerDataManager().getPlayerData(player);
             if (data != null) {
                return data.getKits().keySet().stream().map(String::valueOf).sorted().collect(Collectors.toList());
             }
             return List.of("1","2","3","4","5","6","7");
         });
          commandManager.getCommandCompletions().registerAsyncCompletion("tkits_own_kits", c -> { 
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
         if (combatTagManager.isInternalCombatTagEnabled()) { 
            Bukkit.getPluginManager().registerEvents(combatTagManager, this);
         }
        this.getLogger().info("Listeners registered.");
    }

     private boolean setupPlaceholderAPI() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    private void registerPlaceholders() {
        if (setupPlaceholderAPI()) { 
           new TKitsPlaceholderExpansion(this).register();
           this.getLogger().info("PlaceholderAPI expansion registered.");
        } else {
            
        }
    }

    public void reloadPlugin() {
         this.getLogger().info("Reloading T-Kits...");
         long startTime = System.currentTimeMillis();

         
         playerDataManager.saveAllPlayerData().join(); 

         
          
          

         
         loadConfiguration(); 

         
          combatTagManager.reloadConfigSettings(); 
          cooldownService.loadCooldowns(); 
          messageUtil.reloadMessages(); 
          messageUtil.reloadPrefix(); 
          shareCodeManager.reloadConfigSettings(); 
         
         guiManager.reloadGuiConfig(); 


          
          

          long duration = System.currentTimeMillis() - startTime;
         this.getLogger().info("T-Kits reloaded successfully in " + duration + "ms.");
         messageUtil.logInfo("Plugin configuration reloaded."); 
    }
}
