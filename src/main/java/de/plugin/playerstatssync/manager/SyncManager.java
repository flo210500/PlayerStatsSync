package de.plugin.playerstatssync.manager;

import de.plugin.playerstatssync.PlayerStatsSync;
import de.plugin.playerstatssync.manager.StatConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SyncManager {

    private final PlayerStatsSync plugin;
    private BukkitTask periodicTask;

    public SyncManager(PlayerStatsSync plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------
    //   Periodic task
    // -------------------------------------------------------

    public void startPeriodicSync() {
        int intervalMinutes = plugin.getConfig().getInt("sync-interval-minutes", 20);
        long intervalTicks  = (long) intervalMinutes * 60 * 20;

        periodicTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
            if (online.isEmpty()) return;

            plugin.log("[Periodic] Syncing " + online.size() + " online player(s)...");
            List<ObjectiveConfig.ObjectiveSettings> objectives = ObjectiveConfig.getPeriodicObjectives();
            List<StatConfig.StatEntry>              stats      = StatConfig.getPeriodicEntries();

            for (Player player : online) {
                syncPlayer(player, objectives, stats, "periodic");
            }
        }, intervalTicks, intervalTicks);

        plugin.getLogger().info("Periodic sync started (every " + intervalMinutes + " minute(s)).");
    }

    public void stopPeriodicSync() {
        if (periodicTask != null && !periodicTask.isCancelled()) {
            periodicTask.cancel();
        }
    }

    // -------------------------------------------------------
    //   Sync triggers
    // -------------------------------------------------------

    public void onPlayerJoin(Player player) {
        List<ObjectiveConfig.ObjectiveSettings> objectives = ObjectiveConfig.getJoinObjectives();
        List<StatConfig.StatEntry>              stats      = StatConfig.getJoinEntries();
        if (objectives.isEmpty() && stats.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                syncPlayer(player, objectives, stats, "join"));
    }

    public void onPlayerQuit(Player player) {
        List<ObjectiveConfig.ObjectiveSettings> objectives = ObjectiveConfig.getQuitObjectives();
        List<StatConfig.StatEntry>              stats      = StatConfig.getQuitEntries();
        if (objectives.isEmpty() && stats.isEmpty()) return;

        // Capture all values on main thread before player is removed
        Map<String, Integer> scores     = collectScores(player, objectives);
        Map<String, Integer> statValues = collectStats(player, stats);
        UUID   uuid = player.getUniqueId();
        String name = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long playerId = plugin.getDatabaseManager().upsertPlayer(uuid, name);
                plugin.getDatabaseManager().saveScoresBatch(playerId, scores);
                plugin.getDatabaseManager().saveStatsBatch(playerId, statValues);
                plugin.log("[Quit] Synced " + scores.size() + " objective(s) + "
                        + statValues.size() + " stat(s) for " + name
                        + " (db_id=" + playerId + ")");
            } catch (SQLException e) {
                plugin.logError("Failed to sync on quit for " + name, e);
            }
        });
    }

    public void syncAllOnlinePlayers() {
        List<ObjectiveConfig.ObjectiveSettings> all      = List.copyOf(ObjectiveConfig.getEnabledObjectives());
        List<StatConfig.StatEntry>              allStats = List.copyOf(StatConfig.getEnabledEntries());
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncPlayer(player, all, allStats, "manual");
        }
    }

    // -------------------------------------------------------
    //   Core sync logic
    // -------------------------------------------------------

    private void syncPlayer(Player player,
                            List<ObjectiveConfig.ObjectiveSettings> objectives,
                            List<StatConfig.StatEntry> stats,
                            String trigger) {
        Map<String, Integer> scores     = collectScores(player, objectives);
        Map<String, Integer> statValues = collectStats(player, stats);
        if (scores.isEmpty() && statValues.isEmpty()) return;

        try {
            long playerId = plugin.getDatabaseManager()
                    .upsertPlayer(player.getUniqueId(), player.getName());

            plugin.getDatabaseManager().saveScoresBatch(playerId, scores);
            plugin.getDatabaseManager().saveStatsBatch(playerId, statValues);

            plugin.log("[" + trigger + "] Synced " + scores.size() + " objective(s) + "
                    + statValues.size() + " stat(s) for " + player.getName()
                    + " (uuid=" + player.getUniqueId() + ", db_id=" + playerId + ")");
        } catch (SQLException e) {
            plugin.logError("Failed to sync [" + trigger + "] for " + player.getName(), e);
        }
    }

    private Map<String, Integer> collectScores(Player player,
                                               List<ObjectiveConfig.ObjectiveSettings> objectives) {
        Map<String, Integer> scores = new HashMap<>();
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        for (ObjectiveConfig.ObjectiveSettings setting : objectives) {
            Objective objective = scoreboard.getObjective(setting.getName());
            if (objective == null) continue;

            Score score = objective.getScore(player.getName());
            scores.put(setting.getName(), score.getScore());
        }

        return scores;
    }

    /**
     * Reads Minecraft internal statistic values for the given stat entries.
     * Must be called on the main thread (player.getStatistic is not thread-safe).
     */
    private Map<String, Integer> collectStats(Player player,
                                              List<StatConfig.StatEntry> stats) {
        Map<String, Integer> values = new HashMap<>();
        for (StatConfig.StatEntry entry : stats) {
            try {
                values.put(entry.getKey(), entry.readValue(player));
            } catch (Exception e) {
                plugin.logError("Failed to read stat '" + entry.getKey()
                        + "' for " + player.getName(), e);
            }
        }
        return values;
    }
}