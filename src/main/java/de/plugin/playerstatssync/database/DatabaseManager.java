package de.plugin.playerstatssync.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.plugin.playerstatssync.PlayerStatsSync;
import de.plugin.playerstatssync.manager.ObjectiveConfig;
import de.plugin.playerstatssync.manager.StatConfig;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class DatabaseManager {

    private final PlayerStatsSync plugin;
    private HikariDataSource dataSource;
    private String tablePrefix;

    public DatabaseManager(PlayerStatsSync plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------
    //   Initialization
    // -------------------------------------------------------

    public boolean initialize() {
        tablePrefix = plugin.getConfig().getString("database.table-prefix", "pss_");

        HikariConfig config = new HikariConfig();

        String host     = plugin.getConfig().getString("database.host", "localhost");
        int    port     = plugin.getConfig().getInt("database.port", 3306);
        String dbName   = plugin.getConfig().getString("database.name", "minecraft");
        String user     = plugin.getConfig().getString("database.username", "root");
        String password = plugin.getConfig().getString("database.password", "");

        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + dbName
                + "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true"
                + "&characterEncoding=UTF-8&serverTimezone=UTC");
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        config.setMaximumPoolSize(plugin.getConfig().getInt("database.pool.maximum-pool-size", 10));
        config.setMinimumIdle(plugin.getConfig().getInt("database.pool.minimum-idle", 2));
        config.setConnectionTimeout(plugin.getConfig().getLong("database.pool.connection-timeout", 30000));
        config.setIdleTimeout(plugin.getConfig().getLong("database.pool.idle-timeout", 600000));
        config.setMaxLifetime(plugin.getConfig().getLong("database.pool.max-lifetime", 1800000));
        config.setPoolName("PlayerStatsSync-Pool");

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        try {
            dataSource = new HikariDataSource(config);
            try (Connection conn = dataSource.getConnection()) {
                plugin.getLogger().info("Database connection established successfully.");
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to MySQL database!", e);
            return false;
        }
    }

    /**
     * Creates the players master table and one objective table per configured
     * objective. All tables are generated automatically on startup.
     *
     * Schema (default prefix "pss_"):
     *
     *   pss_players
     *     id        BIGINT PK AUTO_INCREMENT
     *     uuid      VARCHAR(36) UNIQUE   ← Minecraft UUID
     *     name      VARCHAR(16)
     *     last_seen TIMESTAMP
     *
     *   pss_obj_kills
     *     id        BIGINT PK AUTO_INCREMENT
     *     player_id BIGINT FK → pss_players.id (CASCADE)
     *     score     INT
     *     last_sync TIMESTAMP
     *     UNIQUE(player_id)              ← one row per player, UPSERT
     *
     *   pss_obj_deaths  (same structure)
     *   pss_obj_money   (same structure)
     *   ...
     */
    public boolean createTables() {
        try (Connection conn = getConnection()) {

            // Players master table
            String playerTable =
                    "CREATE TABLE IF NOT EXISTS `" + tablePrefix + "players` (" +
                            "  `id`        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT," +
                            "  `uuid`      VARCHAR(36)     NOT NULL," +
                            "  `name`      VARCHAR(16)     NOT NULL," +
                            "  `last_seen` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                            "              ON UPDATE CURRENT_TIMESTAMP," +
                            "  PRIMARY KEY (`id`)," +
                            "  UNIQUE KEY `uq_uuid` (`uuid`)," +
                            "  INDEX `idx_name` (`name`)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(playerTable);
            }
            plugin.getLogger().info("Table '" + tablePrefix + "players' is ready.");

            // One table per objective
            for (ObjectiveConfig.ObjectiveSettings obj : ObjectiveConfig.getEnabledObjectives()) {
                ensureObjectiveTable(conn, obj.getName());
            }

            // One table per statistic entry
            for (StatConfig.StatEntry stat : StatConfig.getEnabledEntries()) {
                ensureStatTable(conn, stat.getKey());
            }

            return true;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create database tables!", e);
            return false;
        }
    }

    /**
     * Creates a single objective table if it doesn't exist yet.
     * Safe to call at runtime (e.g. after /pss reload adds a new objective).
     */
    public void ensureObjectiveTable(Connection conn, String objectiveName) throws SQLException {
        String tableName = objectiveTableName(objectiveName);
        String safeObj   = sanitize(objectiveName);
        String fkName    = "fk_" + safeObj + "_player";
        if (fkName.length() > 64) fkName = fkName.substring(0, 64);

        String sql =
                "CREATE TABLE IF NOT EXISTS `" + tableName + "` (" +
                        "  `id`        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT," +
                        "  `player_id` BIGINT UNSIGNED NOT NULL," +
                        "  `score`     INT             NOT NULL DEFAULT 0," +
                        "  `last_sync` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        "              ON UPDATE CURRENT_TIMESTAMP," +
                        "  PRIMARY KEY (`id`)," +
                        "  UNIQUE KEY `uq_player` (`player_id`)," +
                        "  CONSTRAINT `" + fkName + "`" +
                        "    FOREIGN KEY (`player_id`)" +
                        "    REFERENCES `" + tablePrefix + "players` (`id`)" +
                        "    ON DELETE CASCADE ON UPDATE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
        plugin.getLogger().info("Table '" + tableName + "' is ready.");
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool closed.");
        }
    }

    // -------------------------------------------------------
    //   Player registry
    // -------------------------------------------------------

    /**
     * Upserts the player into pss_players (updates name + last_seen on every
     * call) and returns their internal database id.
     */
    public long upsertPlayer(UUID uuid, String name) throws SQLException {
        String upsertSql =
                "INSERT INTO `" + tablePrefix + "players` (`uuid`, `name`, `last_seen`) " +
                        "VALUES (?, ?, NOW()) " +
                        "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `last_seen` = NOW()";

        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    upsertSql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);
                        if (id > 0) return id;
                    }
                }
            }

            // UPDATE path: fetch existing id
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT `id` FROM `" + tablePrefix + "players` WHERE `uuid` = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong("id");
                }
            }
        }

        throw new SQLException("Could not retrieve player id for UUID " + uuid);
    }

    /**
     * Returns the internal DB id for a player UUID, or null if not yet stored.
     */
    public Long getPlayerId(UUID uuid) throws SQLException {
        String sql = "SELECT `id` FROM `" + tablePrefix + "players` WHERE `uuid` = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("id");
            }
        }
        return null;
    }

    // -------------------------------------------------------
    //   Score operations
    // -------------------------------------------------------

    /**
     * Saves (UPSERT) a batch of scores for one player.
     * Each objective is written to its own table in a single transaction.
     *
     * @param playerDbId  internal id from pss_players
     * @param scores      objectiveName → score value
     */
    public void saveScoresBatch(long playerDbId, Map<String, Integer> scores) throws SQLException {
        if (scores.isEmpty()) return;

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (Map.Entry<String, Integer> entry : scores.entrySet()) {
                    String tableName = objectiveTableName(entry.getKey());
                    String sql =
                            "INSERT INTO `" + tableName + "` (`player_id`, `score`, `last_sync`) " +
                                    "VALUES (?, ?, NOW()) " +
                                    "ON DUPLICATE KEY UPDATE `score` = VALUES(`score`), `last_sync` = NOW()";

                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, playerDbId);
                        ps.setInt(2, entry.getValue());
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Creates a single stat table if it doesn't exist yet.
     * Structure is identical to objective tables – just prefixed with "stat_".
     */
    public void ensureStatTable(Connection conn, String statKey) throws SQLException {
        String tableName = statTableName(statKey);
        String safeKey   = sanitize(statKey);
        String fkName    = "fk_stat_" + safeKey + "_player";
        if (fkName.length() > 64) fkName = fkName.substring(0, 64);

        String sql =
                "CREATE TABLE IF NOT EXISTS `" + tableName + "` (" +
                        "  `id`        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT," +
                        "  `player_id` BIGINT UNSIGNED NOT NULL," +
                        "  `value`     INT             NOT NULL DEFAULT 0," +
                        "  `last_sync` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        "              ON UPDATE CURRENT_TIMESTAMP," +
                        "  PRIMARY KEY (`id`)," +
                        "  UNIQUE KEY `uq_player` (`player_id`)," +
                        "  CONSTRAINT `" + fkName + "`" +
                        "    FOREIGN KEY (`player_id`)" +
                        "    REFERENCES `" + tablePrefix + "players` (`id`)" +
                        "    ON DELETE CASCADE ON UPDATE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
        plugin.getLogger().info("Table '" + tableName + "' is ready.");
    }

    /**
     * Saves (UPSERT) a batch of internal Minecraft stat values for one player.
     * Each stat key has its own table, all writes in a single transaction.
     *
     * @param playerDbId  internal id from pss_players
     * @param stats       statKey → value
     */
    public void saveStatsBatch(long playerDbId, Map<String, Integer> stats) throws SQLException {
        if (stats.isEmpty()) return;

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (Map.Entry<String, Integer> entry : stats.entrySet()) {
                    String tableName = statTableName(entry.getKey());
                    String sql =
                            "INSERT INTO `" + tableName + "` (`player_id`, `value`, `last_sync`) " +
                                    "VALUES (?, ?, NOW()) " +
                                    "ON DUPLICATE KEY UPDATE `value` = VALUES(`value`), `last_sync` = NOW()";

                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, playerDbId);
                        ps.setInt(2, entry.getValue());
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // -------------------------------------------------------
    //   Helpers
    // -------------------------------------------------------

    /** Full table name for a given objective, e.g. "pss_obj_kills" */
    public String objectiveTableName(String objectiveName) {
        return tablePrefix + "obj_" + sanitize(objectiveName);
    }

    /** Full table name for a given stat key, e.g. "pss_stat_deaths" */
    public String statTableName(String statKey) {
        return tablePrefix + "stat_" + sanitize(statKey);
    }

    /** Makes a string safe to use as part of a MySQL table/constraint name */
    private String sanitize(String input) {
        return input.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    public String getTablePrefix() {
        return tablePrefix;
    }
}