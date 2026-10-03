package me.guardian;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GuardPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    static final String[] CHECKS = {"speed", "fly", "nofall", "jesus", "step", "phase", "timer",
            "reach", "killaura", "hitwall", "autoclicker", "blockreach", "blockwall", "nuker",
            "fastplace", "scaffold"};

    private final Map<UUID, PlayerData> data = new HashMap<>();
    private ViolationManager violations;
    private volatile long lastLagSpike = 0;
    private long lastTickNanos = System.nanoTime();
    private volatile double tps = 20.0;
    private long lastTpsNanos = System.nanoTime();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        violations = new ViolationManager(this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new MovementChecks(this), this);
        getServer().getPluginManager().registerEvents(new CombatChecks(this), this);
        getServer().getPluginManager().registerEvents(new WorldChecks(this), this);

        PluginCommand cmd = getCommand("guardian");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Detect server lag spikes so checks don't punish players for server lag
        getServer().getScheduler().runTaskTimer(this, () -> {
            long n = System.nanoTime();
            long gapMs = (n - lastTickNanos) / 1_000_000L;
            lastTickNanos = n;
            if (gapMs > 110) lastLagSpike = System.currentTimeMillis();
        }, 1L, 1L);

        // Measure TPS ourselves: 100 ticks should take 5 seconds
        getServer().getScheduler().runTaskTimer(this, () -> {
            long n = System.nanoTime();
            double seconds = (n - lastTpsNanos) / 1_000_000_000.0;
            lastTpsNanos = n;
            if (seconds > 0) tps = Math.min(20.0, 100.0 / seconds);
        }, 100L, 100L);

        for (Player p : Bukkit.getOnlinePlayers()) data(p);
        getLogger().info("Guardian enabled with " + CHECKS.length + " checks.");
    }

    public ViolationManager violations() {
        return violations;
    }

    public PlayerData data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), PlayerData::new);
    }

    public Collection<PlayerData> allData() {
        return data.values();
    }

    public boolean isLagging() {
        return System.currentTimeMillis() - lastLagSpike < 2500
                || tps < getConfig().getDouble("lag.min-tps", 17.0);
    }

    public double cfg(String path, double def) {
        return getConfig().getDouble(path, def);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        data(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    private void msg(CommandSender s, String text) {
        s.sendMessage(ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("prefix", "&8[&bGuardian&8] ") + text));
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            msg(s, "&7Commands: &f/gac alerts&7, &f/gac status&7, &f/gac info <player>&7, &f/gac reset <player>&7, &f/gac reload");
            return true;
        }
        String sub = a[0].toLowerCase();
        switch (sub) {
            case "alerts": {
                if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return true; }
                if (!p.hasPermission("guardian.alerts")) { msg(s, "&cNo permission."); return true; }
                boolean on = violations.toggleAlerts(p);
                msg(s, "Alerts " + (on ? "&aenabled" : "&cdisabled"));
                return true;
            }
            case "reload":
            case "status":
            case "info":
            case "reset": {
                if (!s.hasPermission("guardian.admin")) { msg(s, "&cNo permission."); return true; }
                break;
            }
            default:
                msg(s, "&cUnknown subcommand.");
                return true;
        }

        if (sub.equals("reload")) {
            reloadConfig();
            msg(s, "&aConfig reloaded.");
        } else if (sub.equals("status")) {
            List<String> on = new ArrayList<>();
            for (String ck : CHECKS) if (violations.enabled(ck)) on.add(ck);
            msg(s, "&7TPS: &f" + Util.f(tps) + " &7| Lagging: &f" + isLagging());
            msg(s, "&7Enabled checks (" + on.size() + "/" + CHECKS.length + "): &f" + String.join(", ", on));
        } else {
            if (a.length < 2) { msg(s, "&cUsage: /gac " + sub + " <player>"); return true; }
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { msg(s, "&cPlayer not online."); return true; }
            PlayerData d = data(t);
            if (sub.equals("reset")) {
                d.vl.clear();
                msg(s, "&aCleared violations for " + t.getName());
            } else {
                msg(s, "&7" + t.getName() + " &8| &7ping &f" + t.getPing() + "ms");
                if (d.vl.isEmpty()) msg(s, "&aNo active violations.");
                d.vl.forEach((k, v) -> msg(s, "&7- &c" + k + " &7VL &f" + Util.f(v)));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : new String[]{"alerts", "status", "info", "reset", "reload"})
                if (x.startsWith(a[0].toLowerCase())) out.add(x);
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("info") || a[0].equalsIgnoreCase("reset"))) {
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
        }
        return out;
    }
}
