package de.plugin.playerstatssync.manager;

import de.plugin.playerstatssync.PlayerStatsSync;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class StatConfig {

    private static final List<StatEntry> entries = new ArrayList<>();

    public static void load(PlayerStatsSync plugin) {
        entries.clear();

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("statistics");
        if (section == null) {
            plugin.getLogger().info("No 'statistics' section in config.yml – skipping internal stats.");
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection statSec = section.getConfigurationSection(key);
            if (statSec == null) continue;
            if (!statSec.getBoolean("enabled", true)) continue;

            String statName          = statSec.getString("statistic", key).toUpperCase();
            boolean syncOnJoin       = statSec.getBoolean("sync-on-join", true);
            boolean syncOnQuit       = statSec.getBoolean("sync-on-quit", true);
            boolean syncPeriodically = statSec.getBoolean("sync-periodically", true);
            String  displayName      = statSec.getString("display-name", key);

            // ── CUSTOM stats (minecraft:custom namespace) ──────────────
            if (statName.equals("CUSTOM")) {
                String customKey = statSec.getString("custom_key", "");
                if (customKey.isBlank()) {
                    plugin.getLogger().warning("Stat '" + key + "' with type CUSTOM requires 'custom_key' – skipping.");
                    continue;
                }
                entries.add(new StatEntry(key, displayName, null, null, null, customKey,
                        syncOnJoin, syncOnQuit, syncPeriodically));
                plugin.getLogger().info("Loaded CUSTOM stat '" + key + "' -> minecraft:custom:" + customKey);
                continue;
            }

            // ── Standard Bukkit Statistic ──────────────────────────────
            Statistic statistic;
            try {
                statistic = Statistic.valueOf(statName);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown statistic '" + statName + "' for entry '" + key + "' – skipping.");
                continue;
            }

            switch (statistic.getType()) {

                case UNTYPED -> entries.add(new StatEntry(
                        key, displayName, statistic, null, null, null,
                        syncOnJoin, syncOnQuit, syncPeriodically));

                case BLOCK, ITEM -> {
                    List<String> materials = statSec.getStringList("materials");
                    if (materials.isEmpty()) {
                        plugin.getLogger().warning("Stat '" + key + "' requires 'materials' list – skipping.");
                        continue;
                    }
                    for (String matName : materials) {
                        Material mat;
                        try {
                            mat = Material.valueOf(matName.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Unknown material '" + matName + "' in stat '" + key + "' – skipping.");
                            continue;
                        }
                        String entryKey = key + "_" + matName.toLowerCase();
                        entries.add(new StatEntry(
                                entryKey, displayName + " (" + mat.name() + ")",
                                statistic, mat, null, null,
                                syncOnJoin, syncOnQuit, syncPeriodically));
                    }
                }

                case ENTITY -> {
                    List<String> entityNames = statSec.getStringList("entities");
                    if (entityNames.isEmpty()) {
                        plugin.getLogger().warning("Stat '" + key + "' requires 'entities' list – skipping.");
                        continue;
                    }
                    for (String entityName : entityNames) {
                        EntityType entityType;
                        try {
                            entityType = EntityType.valueOf(entityName.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Unknown entity '" + entityName + "' in stat '" + key + "' – skipping.");
                            continue;
                        }
                        String entryKey = key + "_" + entityName.toLowerCase();
                        entries.add(new StatEntry(
                                entryKey, displayName + " (" + entityType.name() + ")",
                                statistic, null, entityType, null,
                                syncOnJoin, syncOnQuit, syncPeriodically));
                    }
                }
            }
        }

        plugin.getLogger().info("Loaded " + entries.size() + " statistic entry/entries from config.");
    }

    public static List<StatEntry> getEnabledEntries()    { return Collections.unmodifiableList(entries); }
    public static List<StatEntry> getJoinEntries()       { return entries.stream().filter(StatEntry::isSyncOnJoin).toList(); }
    public static List<StatEntry> getQuitEntries()       { return entries.stream().filter(StatEntry::isSyncOnQuit).toList(); }
    public static List<StatEntry> getPeriodicEntries()   { return entries.stream().filter(StatEntry::isSyncPeriodically).toList(); }

    // -------------------------------------------------------

    public static class StatEntry {
        private final String     key;
        private final String     displayName;
        private final Statistic  statistic;
        private final Material   material;
        private final EntityType entityType;
        private final String     customKey;
        private final boolean    syncOnJoin;
        private final boolean    syncOnQuit;
        private final boolean    syncPeriodically;

        public StatEntry(String key, String displayName, Statistic statistic,
                         Material material, EntityType entityType, String customKey,
                         boolean syncOnJoin, boolean syncOnQuit, boolean syncPeriodically) {
            this.key              = key;
            this.displayName      = displayName;
            this.statistic        = statistic;
            this.material         = material;
            this.entityType       = entityType;
            this.customKey        = customKey;
            this.syncOnJoin       = syncOnJoin;
            this.syncOnQuit       = syncOnQuit;
            this.syncPeriodically = syncPeriodically;
        }

        public int readValue(org.bukkit.entity.Player player) {
            if (customKey != null) {
                try {
                    File statsFile = new File(
                            player.getWorld().getWorldFolder(),
                            "stats/" + player.getUniqueId() + ".json");
                    if (!statsFile.exists()) return 0;

                    String json = new String(Files.readAllBytes(statsFile.toPath()), StandardCharsets.UTF_8);

                    int customIdx = json.indexOf("\"minecraft:custom\"");
                    if (customIdx < 0) return 0;

                    String searchKey = "\"minecraft:" + customKey.toLowerCase() + "\"";
                    int keyIdx = json.indexOf(searchKey, customIdx);
                    if (keyIdx < 0) return 0;

                    int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
                    if (colonIdx < 0) return 0;

                    int start = colonIdx + 1;
                    while (start < json.length() && json.charAt(start) == ' ') start++;
                    int end = start;
                    while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;

                    return Integer.parseInt(json.substring(start, end));
                } catch (Exception e) {
                    return 0;
                }
            }
            if (material != null)   return player.getStatistic(statistic, material);
            if (entityType != null) return player.getStatistic(statistic, entityType);
            return player.getStatistic(statistic);
        }

        public String     getKey()             { return key; }
        public String     getDisplayName()     { return displayName; }
        public Statistic  getStatistic()       { return statistic; }
        public Material   getMaterial()        { return material; }
        public EntityType getEntityType()      { return entityType; }
        public String     getCustomKey()       { return customKey; }
        public boolean    isSyncOnJoin()       { return syncOnJoin; }
        public boolean    isSyncOnQuit()       { return syncOnQuit; }
        public boolean    isSyncPeriodically() { return syncPeriodically; }
    }
}