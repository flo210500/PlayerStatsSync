package de.plugin.playerstatssync.manager;

import de.plugin.playerstatssync.PlayerStatsSync;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

public class ObjectiveConfig {

    private static final Map<String, ObjectiveSettings> objectives = new LinkedHashMap<>();

    public static void load(PlayerStatsSync plugin) {
        objectives.clear();

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("objectives");
        if (section == null) {
            plugin.getLogger().warning("No 'objectives' section found in config.yml!");
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection obj = section.getConfigurationSection(key);
            if (obj == null) continue;

            if (!obj.getBoolean("enabled", true)) continue;

            objectives.put(key, new ObjectiveSettings(
                    key,
                    obj.getString("display-name", key),
                    obj.getBoolean("sync-on-join", true),
                    obj.getBoolean("sync-on-quit", true),
                    obj.getBoolean("sync-periodically", true)
            ));
        }

        plugin.getLogger().info("Loaded " + objectives.size() + " objective(s) from config.");
    }

    public static Collection<ObjectiveSettings> getEnabledObjectives()  { return Collections.unmodifiableCollection(objectives.values()); }
    public static List<ObjectiveSettings> getJoinObjectives()           { return objectives.values().stream().filter(ObjectiveSettings::isSyncOnJoin).toList(); }
    public static List<ObjectiveSettings> getQuitObjectives()           { return objectives.values().stream().filter(ObjectiveSettings::isSyncOnQuit).toList(); }
    public static List<ObjectiveSettings> getPeriodicObjectives()       { return objectives.values().stream().filter(ObjectiveSettings::isSyncPeriodically).toList(); }

    // -------------------------------------------------------

    public static class ObjectiveSettings {
        private final String name, displayName;
        private final boolean syncOnJoin, syncOnQuit, syncPeriodically;

        public ObjectiveSettings(String name, String displayName,
                                 boolean syncOnJoin, boolean syncOnQuit, boolean syncPeriodically) {
            this.name = name;
            this.displayName = displayName;
            this.syncOnJoin = syncOnJoin;
            this.syncOnQuit = syncOnQuit;
            this.syncPeriodically = syncPeriodically;
        }

        public String getName()                { return name; }
        public String getDisplayName()         { return displayName; }
        public boolean isSyncOnJoin()          { return syncOnJoin; }
        public boolean isSyncOnQuit()          { return syncOnQuit; }
        public boolean isSyncPeriodically()    { return syncPeriodically; }
    }
}
