package de.plugin.playerstatssync.updater;

import de.plugin.playerstatssync.PlayerStatsSync;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Checks GitHub Releases for a newer version of PlayerStatsSync.
 *
 * Config keys (under "updater"):
 *   updater.enabled         – set to false to disable entirely (default: true)
 *   updater.github-repo     – owner/repo slug, e.g. "flo210500/PlayerStatsSync"
 *   updater.notify-on-join  – notify admins on join if update available (default: true)
 */
public class UpdateChecker implements Listener {

    private static final String GITHUB_API   = "https://api.github.com/repos/%s/releases/latest";
    private static final String GITHUB_REPO  = "flo210500/PlayerStatsSync";
    private static final String SPIGOT_URL   = "https://www.spigotmc.org/resources/payer-stats-sync.134575/";

    private final PlayerStatsSync plugin;
    private final String           currentVersion;

    // State
    private volatile String  latestVersion  = null;
    private volatile String  downloadUrl    = null;
    private volatile boolean updateAvailable = false;
    private volatile boolean devVersion     = false;
    private volatile boolean checked        = false;

    public UpdateChecker(PlayerStatsSync plugin) {
        this.plugin         = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
    }

    // ── Public API ────────────────────────────────────────────

    /** Runs the version check asynchronously. Call once on enable. */
    public void checkAsync() {
        if (!plugin.getConfig().getBoolean("updater.enabled", true)) {
            plugin.getLogger().info("[Updater] Disabled in config.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String[] result = fetchLatestRelease();
                if (result == null) {
                    plugin.getLogger().warning("[Updater] Could not reach GitHub API.");
                    return;
                }

                latestVersion = result[0];
                downloadUrl   = result[1];

                if (isNewer(latestVersion, currentVersion)) {
                    updateAvailable = true;
                    plugin.getLogger().warning("[Updater] A new version is available: "
                            + latestVersion + " (current: " + currentVersion + ")");
                    plugin.getLogger().warning("[Updater] Download: " + downloadUrl);
                } else if (isNewer(currentVersion, latestVersion)) {
                    devVersion = true;
                    plugin.getLogger().info("[Updater] Running a development version: "
                            + currentVersion + " (latest release: " + latestVersion + ")");
                } else {
                    plugin.getLogger().info("[Updater] Plugin is up to date (" + currentVersion + ").");
                }

            } catch (Exception e) {
                plugin.getLogger().warning("[Updater] Version check failed: " + e.getMessage());
            } finally {
                checked = true;
            }
        });
    }

    public boolean isUpdateAvailable() { return updateAvailable; }
    public boolean isDevVersion()      { return devVersion; }
    public String  getLatestVersion()  { return latestVersion; }
    public String  getCurrentVersion() { return currentVersion; }
    public String  getDownloadUrl()    { return downloadUrl; }
    public boolean hasChecked()        { return checked; }

    // ── Join Notification ─────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("updater.notify-on-join", true)) return;
        if (!updateAvailable) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("playerstatssync.admin")) return;

        // Small delay so it appears after other join messages
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            sendUpdateNotification(player);
        }, 40L); // 2 seconds
    }

    /** Sends the update notification to a specific player. */
    public void sendUpdateNotification(Player player) {
        Component prefix = Component.text("[PlayerStatsSync] ", NamedTextColor.DARK_AQUA, TextDecoration.BOLD);

        player.sendMessage(Component.empty());
        player.sendMessage(prefix
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY)));

        player.sendMessage(prefix
                .append(Component.text("Update available!", NamedTextColor.GREEN, TextDecoration.BOLD)));

        player.sendMessage(prefix
                .append(Component.text("Current: ", NamedTextColor.GRAY))
                .append(Component.text(currentVersion, NamedTextColor.RED)));

        player.sendMessage(prefix
                .append(Component.text("Latest:  ", NamedTextColor.GRAY))
                .append(Component.text(latestVersion, NamedTextColor.GREEN)));

        // Spigot-Link (bevorzugt) und GitHub-Link
        player.sendMessage(prefix
                .append(Component.text("► SpigotMC ", NamedTextColor.GOLD, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(SPIGOT_URL))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Öffne SpigotMC Ressource", NamedTextColor.GRAY))))
                .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("► GitHub Release", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(downloadUrl != null ? downloadUrl : "https://github.com/" + GITHUB_REPO + "/releases/latest"))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Öffne GitHub Release", NamedTextColor.GRAY)))));

        player.sendMessage(prefix
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY)));
        player.sendMessage(Component.empty());
    }

    // ── GitHub API ────────────────────────────────────────────

    /** Returns [tagName, htmlUrl] or null on failure. */
    private String[] fetchLatestRelease() throws Exception {
        URL url = new URL(String.format(GITHUB_API, GITHUB_REPO));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "PlayerStatsSync-UpdateChecker/" + currentVersion);

        if (conn.getResponseCode() != 200) return null;

        // Minimal JSON parsing — no external library needed
        StringBuilder sb = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[1024];
            int read;
            while ((read = reader.read(buf)) != -1) sb.append(buf, 0, read);
        }

        String body = sb.toString();
        String tag     = extractJson(body, "tag_name");
        String htmlUrl = extractJson(body, "html_url");

        if (tag == null) return null;

        // Strip leading 'v' or 'V' and optional dash (v1.0.0 → 1.0.0, v-1.0.0 → 1.0.0)
        if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
        if (tag.startsWith("-")) tag = tag.substring(1);

        return new String[]{tag, htmlUrl};
    }

    /** Extracts a simple string value from JSON without a parser. */
    private String extractJson(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    // ── Version Comparison ────────────────────────────────────

    /**
     * Returns true if candidate is strictly newer than current.
     * Compares semantic versioning (MAJOR.MINOR.PATCH).
     * Falls back to string comparison if parsing fails.
     */
    private boolean isNewer(String candidate, String current) {
        try {
            int[] c = parseVersion(candidate);
            int[] v = parseVersion(current);
            for (int i = 0; i < Math.max(c.length, v.length); i++) {
                int cv = i < c.length ? c[i] : 0;
                int vv = i < v.length ? v[i] : 0;
                if (cv > vv) return true;
                if (cv < vv) return false;
            }
            return false;
        } catch (Exception e) {
            return !candidate.equals(current);
        }
    }

    private int[] parseVersion(String version) {
        // Strip any suffix like -SNAPSHOT, -beta, etc.
        version = version.replaceAll("[^0-9.].*$", "");
        String[] parts = version.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
            nums[i] = Integer.parseInt(parts[i].trim());
        return nums;
    }
}