package com.takeda.tkits.storage;

import com.takeda.tkits.TKits;
import com.takeda.tkits.models.Kit;
import com.takeda.tkits.models.KitContents;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class MySQLStorageHandler implements StorageHandler {

    private final TKits plugin;
    private HikariDataSource dataSource;
    private final ExecutorService executor;
    private final String playerKitsTable; // Store table name

    public MySQLStorageHandler(TKits plugin) {
        this.plugin = plugin;
        // Use a fixed thread pool size based on config, good for DB operations
        int poolSize = plugin.getConfigManager().getMainConfig().getInt("storage.mysql.pool_settings.maximum_pool_size", 5);
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "T-Kits-MySQL-Worker");
            t.setDaemon(true);
            return t;
         });
         this.playerKitsTable = "`tkits_player_kits`"; // Use backticks for safety
    }

    @Override
    public void init() throws SQLException {
        plugin.getMessageUtil().logInfo("Initializing MySQL Storage Handler...");
        ConfigurationSection mysqlConfig = plugin.getConfigManager().getMainConfig().getConfigurationSection("storage.mysql");
        if (mysqlConfig == null) {
            throw new SQLException("MySQL configuration section 'storage.mysql' is missing in config.yml");
        }

        HikariConfig config = new HikariConfig();

         String jdbcUrl = "jdbc:mysql://" +
                 mysqlConfig.getString("host", "localhost") + ":" +
                 mysqlConfig.getInt("port", 3306) + "/" +
                 mysqlConfig.getString("database", "tkits") +
                 mysqlConfig.getString("connection_options", "?autoReconnect=true&useUnicode=true&characterEncoding=utf8");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(mysqlConfig.getString("username", "user"));
        config.setPassword(mysqlConfig.getString("password", "password"));
        try {
             config.setDriverClassName("com.mysql.cj.jdbc.Driver"); // Explicitly set modern driver
         } catch (RuntimeException e) { // Catch if driver class is not found
             throw new SQLException("MySQL JDBC Driver (com.mysql.cj.jdbc.Driver) not found. Ensure it's included.", e);
         }


        ConfigurationSection poolSettings = mysqlConfig.getConfigurationSection("pool_settings");
        config.setMaximumPoolSize(poolSettings != null ? poolSettings.getInt("maximum_pool_size", 10) : 10);
        config.setMinimumIdle(poolSettings != null ? poolSettings.getInt("minimum_idle", config.getMaximumPoolSize() / 2) : 5); // Default minIdle to half maxPool
        config.setIdleTimeout(poolSettings != null ? poolSettings.getLong("idle_timeout", 300000L) : 300000L);
        config.setConnectionTimeout(poolSettings != null ? poolSettings.getLong("connection_timeout", 30000L) : 30000L);
        config.setMaxLifetime(poolSettings != null ? poolSettings.getLong("max_lifetime", 1800000L) : 1800000L);
        // Optional Leak Detection
        // config.setLeakDetectionThreshold(TimeUnit.SECONDS.toMillis(10)); // Example: 10 seconds

        // Recommended HikariCP MySQL optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");

        boolean useSSL = mysqlConfig.getBoolean("useSSL", false);
         config.addDataSourceProperty("useSSL", String.valueOf(useSSL)); // Add as string property
         if (useSSL) {
             // Add verifyServerCertificate=false for basic SSL without full verification if needed for testing
             // config.addDataSourceProperty("verifyServerCertificate", "false");
              plugin.getMessageUtil().logInfo("MySQL SSL enabled.");
         }

        try {
            this.dataSource = new HikariDataSource(config);
            plugin.getMessageUtil().logInfo("MySQL connection pool configured. Verifying connection and table structure...");
            createTables();
            plugin.getMessageUtil().logInfo("MySQL initialization complete.");
        } catch (Exception e) {
            plugin.getMessageUtil().logException("Failed to initialize MySQL connection pool", e);
            if (this.dataSource != null) {
                 this.dataSource.close(); // Ensure pool is closed if setup failed partially
            }
            throw new SQLException("Could not establish initial MySQL connection or setup tables.", e);
        }
    }

     private void createTables() throws SQLException {
         String sql = "CREATE TABLE IF NOT EXISTS " + playerKitsTable + " (" +
                 "`owner_uuid` VARCHAR(36) NOT NULL, " +
                 "`kit_number` TINYINT UNSIGNED NOT NULL, " +
                 "`contents` LONGTEXT DEFAULT NULL, " +
                 "`enderchest_contents` LONGTEXT DEFAULT NULL, " +
                 "`is_global` BOOLEAN NOT NULL DEFAULT FALSE, " +
                 "PRIMARY KEY (`owner_uuid`, `kit_number`)," +
                 "INDEX `idx_global` (`is_global`)" + // Index for efficient global kit lookups
                 ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;"; // Recommended charset/collation

         long startTime = System.nanoTime();
         try (Connection conn = getConnection(); // Use helper to get connection
              Statement stmt = conn.createStatement()) {
             stmt.execute(sql);
              plugin.getLogger().fine("Ensured table " + playerKitsTable + " exists. Time: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime) + "ms");
         } catch (SQLException e) {
             plugin.getMessageUtil().logException("Failed to create or verify table: " + playerKitsTable, e);
             throw e; // Re-throw critical error
         }
    }


    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getMessageUtil().logInfo("MySQL connection pool closed.");
        }
        executor.shutdown();
        try {
             if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                 executor.shutdownNow();
             }
         } catch (InterruptedException e) {
             executor.shutdownNow();
             Thread.currentThread().interrupt();
         }
    }

     // --- Helper Methods for DB Operations ---

     private Connection getConnection() throws SQLException {
         if (dataSource == null || dataSource.isClosed()) {
             throw new SQLException("DataSource is not available.");
         }
         // Optional: Add connection validation or retry logic here if needed
         return dataSource.getConnection();
     }

    // Generic async query execution
    private <T> CompletableFuture<T> executeQueryAsync(SQLFunction<ResultSet, T> handler, String sql, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            String finalSql = sql.replace("?", "{}"); // For logging prepared statement structure
            plugin.getLogger().finest(() -> "Executing SQL Query: " + String.format(finalSql, params));

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                setParameters(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    T result = handler.apply(rs); // Let handler process the result set
                    plugin.getLogger().finest(() -> "SQL Query completed successfully. Time: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + "ms");
                    return result;
                }
            } catch (Exception e) { // Catch broader exceptions from handler.apply too
                 plugin.getMessageUtil().logException("Database query error: " + e.getMessage() + " SQL: " + sql, e);
                 throw new RuntimeException(e); // Propagate for CF error handling
            }
        }, executor);
    }

    // Generic async update/insert/delete execution
    private CompletableFuture<Integer> executeUpdateAsync(String sql, Object... params) {
         return CompletableFuture.supplyAsync(() -> {
             long start = System.nanoTime();
             String finalSql = sql.replace("?", "{}");
             plugin.getLogger().finest(() -> "Executing SQL Update: " + String.format(finalSql, params));

             try (Connection conn = getConnection();
                  PreparedStatement ps = conn.prepareStatement(sql)) {

                 setParameters(ps, params);
                 int rowsAffected = ps.executeUpdate();
                  plugin.getLogger().finest(() -> "SQL Update affected " + rowsAffected + " rows. Time: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + "ms");
                  return rowsAffected;

             } catch (SQLException e) {
                  plugin.getMessageUtil().logException("Database update error: " + e.getMessage() + " SQL: " + sql, e);
                 throw new RuntimeException(e);
             }
         }, executor);
    }

    private void setParameters(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            // Handle UUID -> String conversion automatically
             if (params[i] instanceof UUID) {
                ps.setString(i + 1, params[i].toString());
             } else {
                ps.setObject(i + 1, params[i]);
            }
        }
    }


    // --- Interface Implementation ---

    @Override
    public CompletableFuture<Map<Integer, Kit>> loadPlayerKits(UUID playerUUID) {
         String sql = "SELECT kit_number, contents, enderchest_contents, is_global FROM " + playerKitsTable + " WHERE owner_uuid = ?";
         return executeQueryAsync(rs -> {
            Map<Integer, Kit> kits = new HashMap<>();
             while (rs.next()) {
                 int kitNumber = rs.getInt("kit_number");
                  try {
                     String contentsData = rs.getString("contents");
                     String echestData = rs.getString("enderchest_contents");
                     boolean isGlobal = rs.getBoolean("is_global");

                     KitContents contents = KitContents.deserialize(contentsData);
                     KitContents echestContents = KitContents.deserialize(echestData);

                     kits.put(kitNumber, Kit.builder()
                             .kitNumber(kitNumber)
                             .owner(playerUUID)
                             .name("Kit " + kitNumber) // Standard name
                             .contents(contents)
                             .enderChestContents(echestContents)
                             .global(isGlobal)
                             .build());
                 } catch (Exception e) { // Catch deserialization or other errors per kit
                      plugin.getMessageUtil().logException("Failed to deserialize kit " + kitNumber + " for player " + playerUUID, e);
                      // Skip this kit but continue loading others
                 }
            }
            return kits;
        }, sql, playerUUID); // Pass UUID directly, setParameters handles toString
    }


    @Override
    public CompletableFuture<Void> savePlayerKit(UUID playerUUID, Kit kit) {
        // Ensure consistency
        if (!kit.getOwner().equals(playerUUID) || kit.getKitNumber() <= 0) {
             plugin.getMessageUtil().logSevere("Invalid Kit object passed to savePlayerKit: Mismatched owner/invalid number. Aborting save. Kit: " + kit);
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid Kit data for saving."));
        }

         String sql = "INSERT INTO " + playerKitsTable + " (owner_uuid, kit_number, contents, enderchest_contents, is_global) " +
                 "VALUES (?, ?, ?, ?, ?) " +
                 "ON DUPLICATE KEY UPDATE " +
                 "contents = VALUES(contents), " +
                 "enderchest_contents = VALUES(enderchest_contents), " +
                 "is_global = VALUES(is_global)";
        try {
            String contentsData = KitContents.serialize(kit.getContents());
            String echestData = KitContents.serialize(kit.getEnderChestContents());

            return executeUpdateAsync(sql,
                     kit.getOwner(), // Pass UUID directly
                     kit.getKitNumber(),
                     contentsData,
                     echestData,
                     kit.isGlobal()
            ).thenAccept(rowsAffected -> { // Void return type needed
                 if (rowsAffected == 0) {
                      plugin.getLogger().warning("Upsert operation for kit " + kit.getKitNumber() + " player " + kit.getOwner() + " affected 0 rows unexpectedly.");
                 }
             });
         } catch (IllegalStateException e) {
             plugin.getMessageUtil().logException("Failed to serialize kit " + kit.getKitNumber() + " for player " + kit.getOwner() + " before DB save.", e);
             return CompletableFuture.failedFuture(e);
         }
    }

    @Override
    public CompletableFuture<Void> deletePlayerKit(UUID playerUUID, int kitNumber) {
        String sql = "DELETE FROM " + playerKitsTable + " WHERE owner_uuid = ? AND kit_number = ?";
        return executeUpdateAsync(sql, playerUUID, kitNumber).thenAccept(rows -> {}); // Convert to Void CompletableFuture
    }

    @Override
    public CompletableFuture<List<Kit>> loadGlobalKits() {
        String sql = "SELECT owner_uuid, kit_number, contents, enderchest_contents FROM " + playerKitsTable + " WHERE is_global = TRUE";
         return executeQueryAsync(rs -> {
            List<Kit> globalKits = new ArrayList<>();
            while (rs.next()) {
                try {
                     UUID ownerUUID = UUID.fromString(rs.getString("owner_uuid")); // Get owner from result set
                     int kitNumber = rs.getInt("kit_number");
                     String contentsData = rs.getString("contents");
                     String echestData = rs.getString("enderchest_contents");

                     KitContents contents = KitContents.deserialize(contentsData);
                     KitContents echestContents = KitContents.deserialize(echestData);

                    globalKits.add(Kit.builder()
                            .kitNumber(kitNumber)
                            .owner(ownerUUID)
                            .name("Kit " + kitNumber)
                            .contents(contents)
                            .enderChestContents(echestContents)
                            .global(true) // We queried for global=true
                            .build());
                } catch (Exception e) { // Catch deserialization, UUID format etc.
                     plugin.getMessageUtil().logException("Failed to deserialize global kit data from DB for kit num " + rs.getInt("kit_number"), e);
                }
            }
            return globalKits;
         }, sql);
    }

    @Override
    public CompletableFuture<Void> saveGlobalKit(Kit kit) {
        // This just calls savePlayerKit, ensuring the 'is_global' flag is set correctly in that method
         if (!kit.isGlobal()) {
              plugin.getMessageUtil().logWarning("saveGlobalKit called with a kit object where isGlobal=false. Kit: " + kit.getOwner() + "_" + kit.getKitNumber());
             // Ensure it's saved correctly (as non-global)
         }
         // Ensure kit object is marked global before passing if this method is the definitive 'make global' action
          Kit ensuredGlobalKit = kit.toBuilder().global(true).build();
          return savePlayerKit(ensuredGlobalKit.getOwner(), ensuredGlobalKit);
    }

    @Override
    public CompletableFuture<Void> deleteGlobalKit(UUID ownerUUID, int kitNumber) {
        // Mark the kit as no longer global in the database
        String sql = "UPDATE " + playerKitsTable + " SET is_global = FALSE WHERE owner_uuid = ? AND kit_number = ? AND is_global = TRUE";
         return executeUpdateAsync(sql, ownerUUID, kitNumber).thenAccept(rows -> {
            if (rows == 0) {
                plugin.getLogger().fine(() -> "Attempted to delete global status for non-existent/non-global kit: " + ownerUUID + "_" + kitNumber);
            }
        });
    }

    // --- Migration Methods (MySQL -> Target) ---

    @Override
     public CompletableFuture<Void> migratePlayerData(UUID playerUUID, StorageHandler targetHandler) {
         return loadPlayerKits(playerUUID).thenComposeAsync(kits -> {
              if (kits.isEmpty()) {
                  plugin.getMessageUtil().logInfo("No MySQL data found for player " + playerUUID + " to migrate.");
                  return CompletableFuture.completedFuture(null);
              }
               plugin.getMessageUtil().logInfo("Migrating " + kits.size() + " kits for player " + playerUUID + " from MySQL to " + targetHandler.getClass().getSimpleName() + "...");
              List<CompletableFuture<Void>> saveFutures = kits.values().stream()
                 .map(kit -> targetHandler.savePlayerKit(playerUUID, kit)
                      .exceptionally(ex -> {
                           plugin.getMessageUtil().logException("Failed migrating kit " + kit.getKitNumber() + " for player " + playerUUID, ex);
                           return null;
                       })
                  )
                 .collect(Collectors.toList());
             return CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0]));
         }, executor);
     }

     @Override
     public CompletableFuture<Void> migrateAllData(StorageHandler targetHandler) {
          plugin.getMessageUtil().logInfo("Starting full migration from MySQL to " + targetHandler.getClass().getSimpleName() + "...");
          List<CompletableFuture<Void>> allMigrations = new ArrayList<>();

         // 1. Get all distinct player UUIDs with kits
         String selectDistinctPlayersSQL = "SELECT DISTINCT owner_uuid FROM " + playerKitsTable;
         allMigrations.add(
             executeQueryAsync(rs -> {
                List<UUID> playerUUIDs = new ArrayList<>();
                while (rs.next()) {
                    try {
                        playerUUIDs.add(UUID.fromString(rs.getString("owner_uuid")));
                    } catch (IllegalArgumentException e) {
                         plugin.getMessageUtil().logWarning("Invalid UUID found in database during migration query: " + rs.getString("owner_uuid"));
                    }
                }
                return playerUUIDs;
             }, selectDistinctPlayersSQL)
             .thenComposeAsync(playerUUIDs -> {
                 plugin.getMessageUtil().logInfo("Found " + playerUUIDs.size() + " players with data to potentially migrate from MySQL...");
                 List<CompletableFuture<Void>> playerMigrationFutures = new ArrayList<>();
                 for (UUID playerUUID : playerUUIDs) {
                     playerMigrationFutures.add(migratePlayerData(playerUUID, targetHandler));
                 }
                 return CompletableFuture.allOf(playerMigrationFutures.toArray(new CompletableFuture[0]));
             }, executor)
         );

         // Combine and Finalize
         return CompletableFuture.allOf(allMigrations.toArray(new CompletableFuture[0]))
                  .thenRun(() -> {
                       plugin.getMessageUtil().logInfo("MySQL migration process finished successfully.");
                       plugin.getMessageUtil().logWarning("REMEMBER TO CHANGE 'storage.type' in config.yml and RESTART the server!");
                   })
                  .exceptionally(ex -> {
                       plugin.getMessageUtil().logException("Critical error during MySQL migration process.", ex);
                      return null;
                  });
     }


    // Functional interface for cleaner lambda in executeQueryAsync
    @FunctionalInterface
    interface SQLFunction<T, R> {
        R apply(T t) throws SQLException, IOException, ClassNotFoundException; // Allow expected exceptions
    }
}