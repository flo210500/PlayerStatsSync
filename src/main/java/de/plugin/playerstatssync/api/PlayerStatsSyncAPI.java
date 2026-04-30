package de.plugin.playerstatssync.api;

import de.plugin.playerstatssync.PlayerStatsSync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Public API for other plugins to read synced stats and objectives.
 *
 * All methods are synchronous DB reads. Call them off the main thread
 * if you need to avoid any potential latency, e.g.:
 *
 *   Bukkit.getScheduler().runTaskAsynchronously(yourPlugin, () -> {
 *       long deaths = PlayerStatsSyncAPI.getStat(uuid, "deaths");
 *       // use deaths...
 *   });
 *
 * Usage example:
 *
 *   // Get a player's synced death count
 *   long deaths = PlayerStatsSyncAPI.getStat(uuid, "deaths");
 *   if (deaths == PlayerStatsSyncAPI.NOT_FOUND) {
 *       // player not in database yet
 *   }
 *
 *   // Get a scoreboard objective value (e.g. currency)
 *   long balance = PlayerStatsSyncAPI.getObjective(uuid, "money");
 *
 *   // Get a kill entity stat
 *   long creeperKills = PlayerStatsSyncAPI.getStat(uuid, "kill_entity_creeper");
 */
public final class PlayerStatsSyncAPI {

    /**
     * Sentinel value returned when the player or stat is not found in the database.
     */
    public static final long NOT_FOUND = Long.MIN_VALUE;

    private PlayerStatsSyncAPI() {}

    // ── Stat tables (sbsync_stat_*) ──────────────────────────

    /**
     * Returns the synced value for a stat table key.
     *
     * @param uuid    Player UUID
     * @param statKey Stat table key, e.g. "deaths", "playtime", "kill_entity_creeper"
     * @return The synced value, or {@link #NOT_FOUND} if not in the database.
     */
    public static long getStat(UUID uuid, String statKey) {
        return queryValue(uuid, tableName("stat_" + statKey), "value");
    }

    // ── Objective tables (sbsync_obj_*) ──────────────────────

    /**
     * Returns the synced value for a scoreboard objective.
     *
     * @param uuid          Player UUID
     * @param objectiveName Objective name as configured, e.g. "money", "kills"
     * @return The synced score, or {@link #NOT_FOUND} if not in the database.
     */
    public static long getObjective(UUID uuid, String objectiveName) {
        return queryValue(uuid, tableName("obj_" + objectiveName), "score");
    }

    // ── Player info ───────────────────────────────────────────

    /**
     * Returns the last known player name from the database.
     *
     * @param uuid Player UUID
     * @return Last known name, or null if the player is not in the database.
     */
    public static String getPlayerName(UUID uuid) {
        PlayerStatsSync plugin = PlayerStatsSync.getInstance();
        if (plugin == null) return null;

        String prefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "SELECT `name` FROM `" + prefix + "players` WHERE `uuid` = ?";

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("name");
            }
        } catch (Exception e) {
            plugin.logError("[API] getPlayerName failed for " + uuid, e);
        }
        return null;
    }

    /**
     * Returns true if the player has an entry in the database.
     *
     * @param uuid Player UUID
     * @return true if the player exists in the database.
     */
    public static boolean isPlayerTracked(UUID uuid) {
        PlayerStatsSync plugin = PlayerStatsSync.getInstance();
        if (plugin == null) return false;

        String prefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "SELECT 1 FROM `" + prefix + "players` WHERE `uuid` = ? LIMIT 1";

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            plugin.logError("[API] isPlayerTracked failed for " + uuid, e);
        }
        return false;
    }

    // ── Internal helpers ──────────────────────────────────────

    private static long queryValue(UUID uuid, String table, String column) {
        PlayerStatsSync plugin = PlayerStatsSync.getInstance();
        if (plugin == null) return NOT_FOUND;

        String prefix = plugin.getDatabaseManager().getTablePrefix();
        String sql =
                "SELECT t.`" + column + "` " +
                "FROM `" + table + "` t " +
                "JOIN `" + prefix + "players` p ON p.`id` = t.`player_id` " +
                "WHERE p.`uuid` = ?";

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(column);
            }
        } catch (Exception e) {
            // Table might not exist yet if stat was never synced — log only as warning
            plugin.getLogger().warning("[API] queryValue failed for table '" + table + "': " + e.getMessage());
        }
        return NOT_FOUND;
    }

    private static String tableName(String key) {
        PlayerStatsSync plugin = PlayerStatsSync.getInstance();
        if (plugin == null) return key;
        return plugin.getDatabaseManager().getTablePrefix() + key.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }
}
