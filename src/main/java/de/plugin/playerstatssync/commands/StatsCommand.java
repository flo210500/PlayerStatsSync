package de.plugin.playerstatssync.commands;

import de.plugin.playerstatssync.PlayerStatsSync;
import de.plugin.playerstatssync.manager.ObjectiveConfig;
import de.plugin.playerstatssync.manager.StatConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

public class StatsCommand implements CommandExecutor, TabCompleter {

    private final PlayerStatsSync plugin;

    public StatsCommand(PlayerStatsSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!sender.hasPermission("playerstatssync.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) { sendHelp(sender); return true; }

        switch (args[0].toLowerCase()) {

            case "reload" -> {
                plugin.reload();
                sender.sendMessage(Component.text(
                        "[PlayerStatsSync] Reloaded. "
                                + ObjectiveConfig.getEnabledObjectives().size()
                                + " objective(s) active.", NamedTextColor.GREEN));
            }

            case "sync" -> {
                // /pss sync [Spielername|@a|@r|@p|@e]
                if (args.length >= 2) {
                    String arg = args[1];
                    List<org.bukkit.entity.Player> targets = new java.util.ArrayList<>();

                    if (arg.startsWith("@")) {
                        // Selektor — benötigt einen CommandSender mit Location
                        try {
                            Bukkit.selectEntities(sender, arg).stream()
                                    .filter(e -> e instanceof org.bukkit.entity.Player)
                                    .map(e -> (org.bukkit.entity.Player) e)
                                    .forEach(targets::add);
                        } catch (IllegalArgumentException e) {
                            sender.sendMessage(Component.text(
                                    "[PlayerStatsSync] Invalid selector: " + arg, NamedTextColor.RED));
                            return true;
                        }
                    } else {
                        // Einzelner Spielername
                        org.bukkit.entity.Player target = Bukkit.getPlayerExact(arg);
                        if (target != null) targets.add(target);
                    }

                    if (targets.isEmpty()) {
                        sender.sendMessage(Component.text(
                                "[PlayerStatsSync] No online players found for: " + arg,
                                NamedTextColor.RED));
                        return true;
                    }

                    // Spieler-Namen für Async-Thread einfrieren
                    final List<org.bukkit.entity.Player> finalTargets = List.copyOf(targets);
                    sender.sendMessage(Component.text(
                            "[PlayerStatsSync] Syncing " + finalTargets.size() + " player(s)...",
                            NamedTextColor.AQUA));

                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        int synced = 0;
                        for (org.bukkit.entity.Player t : finalTargets) {
                            if (plugin.getSyncManager().syncSinglePlayer(t)) synced++;
                        }
                        sender.sendMessage(Component.text(
                                "[PlayerStatsSync] Done – synced " + synced + " player(s).",
                                NamedTextColor.GREEN));
                    });

                } else {
                    // Alle Online-Spieler
                    int count = Bukkit.getOnlinePlayers().size();
                    if (count == 0) {
                        sender.sendMessage(Component.text(
                                "[PlayerStatsSync] No players online.", NamedTextColor.YELLOW));
                        return true;
                    }
                    sender.sendMessage(Component.text(
                            "[PlayerStatsSync] Syncing " + count + " player(s)...", NamedTextColor.AQUA));
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        plugin.getSyncManager().syncAllOnlinePlayers();
                        sender.sendMessage(Component.text(
                                "[PlayerStatsSync] Done – synced " + count + " player(s).",
                                NamedTextColor.GREEN));
                    });
                }
            }

            case "status" -> {
                boolean db       = plugin.getDatabaseManager().isConnected();
                int     online   = Bukkit.getOnlinePlayers().size();
                int     interval = plugin.getConfig().getInt("sync-interval-minutes", 20);
                String  prefix   = plugin.getDatabaseManager().getTablePrefix();

                sender.sendMessage(Component.text("─── PlayerStatsSync Status ───", NamedTextColor.GOLD));
                sender.sendMessage(line("DB Connected ", db ? "✔ Yes" : "✘ No",
                        db ? NamedTextColor.GREEN : NamedTextColor.RED));
                sender.sendMessage(line("Players online", String.valueOf(online), NamedTextColor.WHITE));
                sender.sendMessage(line("Sync interval ", interval + " min", NamedTextColor.WHITE));
                sender.sendMessage(line("Table prefix  ", prefix, NamedTextColor.WHITE));
                sender.sendMessage(Component.text("Objectives & tables:", NamedTextColor.GRAY));

                for (ObjectiveConfig.ObjectiveSettings s : ObjectiveConfig.getEnabledObjectives()) {
                    String table = plugin.getDatabaseManager().objectiveTableName(s.getName());
                    sender.sendMessage(
                            Component.text("  • ", NamedTextColor.DARK_GRAY)
                                    .append(Component.text(s.getName(), NamedTextColor.AQUA))
                                    .append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                                    .append(Component.text(table, NamedTextColor.WHITE))
                                    .append(Component.text(
                                            " [join=" + s.isSyncOnJoin()
                                                    + " quit=" + s.isSyncOnQuit()
                                                    + " periodic=" + s.isSyncPeriodically() + "]",
                                            NamedTextColor.DARK_GRAY)));
                }

                sender.sendMessage(Component.text("Statistics & tables:", NamedTextColor.GRAY));
                if (StatConfig.getEnabledEntries().isEmpty()) {
                    sender.sendMessage(Component.text("  (none configured)", NamedTextColor.DARK_GRAY));
                } else {
                    for (StatConfig.StatEntry s : StatConfig.getEnabledEntries()) {
                        String table = plugin.getDatabaseManager().statTableName(s.getKey());
                        sender.sendMessage(
                                Component.text("  • ", NamedTextColor.DARK_GRAY)
                                        .append(Component.text(s.getKey(), NamedTextColor.GREEN))
                                        .append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                                        .append(Component.text(table, NamedTextColor.WHITE))
                                        .append(Component.text(
                                                " [join=" + s.isSyncOnJoin()
                                                        + " quit=" + s.isSyncOnQuit()
                                                        + " periodic=" + s.isSyncPeriodically() + "]",
                                                NamedTextColor.DARK_GRAY)));
                    }
                }
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    private Component line(String label, String value, NamedTextColor valueColor) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, valueColor));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("─── PlayerStatsSync Commands ───", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/pss reload          ", NamedTextColor.AQUA)
                .append(Component.text("– Reload config & ensure tables", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/pss sync                   ", NamedTextColor.AQUA)
                .append(Component.text("– Force-sync all online players", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/pss sync <player|@a|@r|@p> ", NamedTextColor.AQUA)
                .append(Component.text("– Force-sync specific player(s)", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/pss status          ", NamedTextColor.AQUA)
                .append(Component.text("– Show DB status & table layout", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return Arrays.asList("reload", "sync", "status");
        if (args.length == 2 && args[0].equalsIgnoreCase("sync")) {
            String partial = args[1].toLowerCase();
            List<String> suggestions = new java.util.ArrayList<>();
            // Selektoren
            List.of("@a", "@r", "@p", "@e").stream()
                    .filter(s -> s.startsWith(partial))
                    .forEach(suggestions::add);
            // Online-Spielernamen
            Bukkit.getOnlinePlayers().stream()
                    .map(org.bukkit.entity.Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .forEach(suggestions::add);
            return suggestions;
        }
        return List.of();
    }
}