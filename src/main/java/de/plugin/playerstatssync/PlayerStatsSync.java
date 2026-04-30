package de.plugin.playerstatssync;

import de.plugin.playerstatssync.commands.StatsCommand;
import de.plugin.playerstatssync.commands.VerifyCommand;
import de.plugin.playerstatssync.database.DatabaseManager;
import de.plugin.playerstatssync.listener.PlayerListener;
import de.plugin.playerstatssync.manager.ObjectiveConfig;
import de.plugin.playerstatssync.manager.StatConfig;
import de.plugin.playerstatssync.manager.SyncManager;
import de.plugin.playerstatssync.placeholder.StatsSyncPlaceholder;
import de.plugin.playerstatssync.updater.UpdateChecker;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.logging.Level;

public final class PlayerStatsSync extends JavaPlugin {

    private static PlayerStatsSync instance;
    private DatabaseManager databaseManager;
    private SyncManager     syncManager;
    private UpdateChecker   updateChecker;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Fehlende Keys nachtragen — statistics & objectives werden NICHT überschrieben
        mergeNewConfigKeys();

        ObjectiveConfig.load(this);
        StatConfig.load(this);

        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Failed to connect to database! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!databaseManager.createTables()) {
            getLogger().severe("Failed to create database tables! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        syncManager = new SyncManager(this);
        syncManager.startPeriodicSync();

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        StatsCommand cmd = new StatsCommand(this);
        getCommand("playerstatssync").setExecutor(cmd);
        getCommand("playerstatssync").setTabCompleter(cmd);

        getCommand("verify").setExecutor(new VerifyCommand(this));

        // PlaceholderAPI — optional
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new StatsSyncPlaceholder(this).register();
            getLogger().info("PlaceholderAPI found — placeholders registered.");
        } else {
            getLogger().info("PlaceholderAPI not found — placeholders disabled.");
        }

        // Update Checker
        updateChecker = new UpdateChecker(this);
        updateChecker.checkAsync();
        getServer().getPluginManager().registerEvents(updateChecker, this);

        getLogger().info("PlayerStatsSync enabled! Syncing "
                + ObjectiveConfig.getEnabledObjectives().size() + " objective(s) + "
                + StatConfig.getEnabledEntries().size() + " stat(s). "
                + "Prefix: " + databaseManager.getTablePrefix());
    }

    @Override
    public void onDisable() {
        if (syncManager != null) {
            getLogger().info("Syncing all online players before shutdown...");
            syncManager.syncAllOnlinePlayers();
            syncManager.stopPeriodicSync();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("PlayerStatsSync disabled.");
    }

    public void reload() {
        reloadConfig();
        mergeNewConfigKeys();

        ObjectiveConfig.load(this);
        StatConfig.load(this);

        for (ObjectiveConfig.ObjectiveSettings obj : ObjectiveConfig.getEnabledObjectives()) {
            try (var conn = databaseManager.getConnection()) {
                databaseManager.ensureObjectiveTable(conn, obj.getName());
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Could not ensure table for objective: " + obj.getName(), e);
            }
        }

        for (StatConfig.StatEntry stat : StatConfig.getEnabledEntries()) {
            try (var conn = databaseManager.getConnection()) {
                databaseManager.ensureStatTable(conn, stat.getKey());
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Could not ensure table for stat: " + stat.getKey(), e);
            }
        }

        if (syncManager != null) {
            syncManager.stopPeriodicSync();
            syncManager.startPeriodicSync();
        }
    }

    // ── Selektives Config-Merge ──────────────────────────────
    // Kopiert nur Top-Level-Keys aus der JAR-config.yml die noch
    // nicht in der bestehenden config.yml vorhanden sind.
    // "statistics" und "objectives" werden niemals angefasst.
    // ────────────────────────────────────────────────────────
    private static final Set<String> PROTECTED_SECTIONS = Set.of("statistics", "objectives");

    private void mergeNewConfigKeys() {
        var jarStream = getResource("config.yml");
        if (jarStream == null) return;

        FileConfiguration jarConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(jarStream, StandardCharsets.UTF_8));

        FileConfiguration live = getConfig();
        boolean changed = false;

        for (String key : jarConfig.getKeys(false)) {
            if (PROTECTED_SECTIONS.contains(key)) continue; // niemals überschreiben
            if (!live.contains(key)) {
                live.set(key, jarConfig.get(key));
                getLogger().info("Config: neuer Schlüssel hinzugefügt: " + key);
                changed = true;
            }
        }

        if (changed) saveConfig();
    }

    // -------------------------------------------------------

    public static PlayerStatsSync getInstance()  { return instance; }
    public DatabaseManager getDatabaseManager()  { return databaseManager; }
    public SyncManager     getSyncManager()      { return syncManager; }
    public UpdateChecker   getUpdateChecker()    { return updateChecker; }

    public void log(String message) {
        if (getConfig().getBoolean("logging.log-sync-actions", true))
            getLogger().info(message);
    }

    public void logError(String message, Throwable t) {
        if (getConfig().getBoolean("logging.log-errors", true))
            getLogger().log(Level.SEVERE, message, t);
    }
}