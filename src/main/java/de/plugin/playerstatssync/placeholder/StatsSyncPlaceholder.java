package de.plugin.playerstatssync.placeholder;

import de.plugin.playerstatssync.PlayerStatsSync;
import de.plugin.playerstatssync.api.PlayerStatsSyncAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * PlaceholderAPI expansion for PlayerStatsSync.
 *
 * Available placeholders:
 *
 *   %playerstatssync_stat_<key>%
 *       Returns the synced value for a stat table key.
 *       Example: %playerstatssync_stat_deaths%
 *                %playerstatssync_stat_kill_entity_creeper%
 *                %playerstatssync_stat_playtime%
 *
 *   %playerstatssync_obj_<name>%
 *       Returns the synced value for a scoreboard objective.
 *       Example: %playerstatssync_obj_kills%
 *                %playerstatssync_obj_money%
 *
 *   %playerstatssync_player_name%
 *       Returns the player's last known name from the database.
 *
 * All lookups are synchronous DB reads — keep usage
 * to low-frequency contexts (scoreboards refreshing every few seconds).
 * For high-frequency use, consider caching on your end.
 */
public class StatsSyncPlaceholder extends PlaceholderExpansion {

    private final PlayerStatsSync plugin;

    public StatsSyncPlaceholder(PlayerStatsSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() { return "playerstatssync"; }

    @Override
    public @NotNull String getAuthor() { return "Shin_Jin_Jin"; }

    @Override
    public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }

    @Override
    public boolean persist() { return true; }

    @Override
    public boolean canRegister() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        UUID uuid = player.getUniqueId();

        // %playerstatssync_stat_<key>%
        if (params.startsWith("stat_")) {
            String key = params.substring(5); // strip "stat_"
            long value = PlayerStatsSyncAPI.getStat(uuid, key);
            return value == PlayerStatsSyncAPI.NOT_FOUND ? "" : String.valueOf(value);
        }

        // %playerstatssync_obj_<name>%
        if (params.startsWith("obj_")) {
            String name = params.substring(4); // strip "obj_"
            long value = PlayerStatsSyncAPI.getObjective(uuid, name);
            return value == PlayerStatsSyncAPI.NOT_FOUND ? "" : String.valueOf(value);
        }

        // %playerstatssync_player_name%
        if (params.equals("player_name")) {
            String name = PlayerStatsSyncAPI.getPlayerName(uuid);
            return name != null ? name : player.getName() != null ? player.getName() : "";
        }

        return null; // unknown placeholder
    }
}
