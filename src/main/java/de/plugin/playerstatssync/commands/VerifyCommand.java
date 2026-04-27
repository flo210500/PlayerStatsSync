package de.plugin.playerstatssync.commands;

import de.plugin.playerstatssync.PlayerStatsSync;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * /verify <6-digit-code>
 *
 * Sends a HMAC-SHA256-signed POST request to the WordPress REST endpoint.
 * Config keys (in config.yml, section "verify"):
 *   verify.wordpress-url   – full URL to /wp-json/msp/v1/verify
 *   verify.hmac-secret     – shared secret (identical in WP plugin)
 *   verify.website-url     – shown in usage hint to the player
 *   verify.enabled         – set to false to disable the command
 */
public class VerifyCommand implements CommandExecutor {

    private final PlayerStatsSync plugin;

    public VerifyCommand(PlayerStatsSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command is for players only.", NamedTextColor.RED));
            return true;
        }

        if (!plugin.getConfig().getBoolean("verify.enabled", true)) {
            player.sendMessage(Component.text("Account linking is currently disabled.", NamedTextColor.RED));
            return true;
        }

        if (args.length != 1 || !args[0].matches("\\d{6}")) {
            String site = plugin.getConfig().getString("verify.website-url", "the website");
            player.sendMessage(Component.text("Usage: /verify <6-digit code>", NamedTextColor.RED));
            player.sendMessage(Component.text("Get your code at: " + site, NamedTextColor.GRAY));
            return true;
        }

        String code      = args[0];
        String uuid      = player.getUniqueId().toString();
        long   timestamp = System.currentTimeMillis() / 1000L;
        String secret    = plugin.getConfig().getString("verify.hmac-secret", "");
        String wpUrl     = plugin.getConfig().getString("verify.wordpress-url", "");

        if (secret.isBlank() || wpUrl.isBlank()) {
            player.sendMessage(Component.text(
                    "Verify is not configured yet. Please contact an administrator.", NamedTextColor.RED));
            plugin.getLogger().severe("[Verify] verify.hmac-secret or verify.wordpress-url is not set in config.yml!");
            return true;
        }

        final String signature;
        try {
            signature = hmacSha256(uuid + ":" + code + ":" + timestamp, secret);
        } catch (Exception e) {
            player.sendMessage(Component.text("Internal error while signing request.", NamedTextColor.RED));
            plugin.logError("[Verify] HMAC signing failed", e);
            return true;
        }

        player.sendMessage(Component.text("⏳ Verifying...", NamedTextColor.YELLOW));

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int status;
            try {
                status = postVerify(wpUrl, uuid, code, timestamp, signature);
            } catch (Exception e) {
                plugin.logError("[Verify] HTTP request failed for " + player.getName(), e);
                status = -1;
            }

            final int finalStatus = status;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                switch (finalStatus) {
                    case 200 -> {
                        player.sendMessage(Component.text(
                                "✔ Account linked successfully! Reload the website to see your stats.",
                                NamedTextColor.GREEN));
                        plugin.log("[Verify] " + player.getName() + " (" + uuid + ") linked successfully.");
                    }
                    case 401 -> player.sendMessage(Component.text(
                            "✘ Request timed out on the server. Please try again.", NamedTextColor.RED));
                    case 403 -> player.sendMessage(Component.text(
                            "✘ Invalid signature. Please contact an administrator.", NamedTextColor.RED));
                    case 404 -> player.sendMessage(Component.text(
                            "✘ Code invalid or expired. Request a new code on the website.", NamedTextColor.RED));
                    default  -> player.sendMessage(Component.text(
                            "✘ Server error (" + finalStatus + "). Please try again later.", NamedTextColor.RED));
                }
            });
        });

        return true;
    }

    // ── HTTP POST ────────────────────────────────────────────
    private int postVerify(String endpoint, String uuid, String code,
                           long timestamp, String signature) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "PlayerStatsSync-Verify/1.0");

        String body = "{\"uuid\":\"" + uuid + "\",\"code\":\"" + code
                + "\",\"timestamp\":" + timestamp
                + ",\"signature\":\"" + signature + "\"}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        return conn.getResponseCode();
    }

    // ── HMAC-SHA256 ──────────────────────────────────────────
    private String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
