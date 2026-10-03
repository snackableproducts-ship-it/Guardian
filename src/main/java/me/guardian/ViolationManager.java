package me.guardian;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ViolationManager {
    private final GuardPlugin plugin;
    private final Set<UUID> alertsOff = new HashSet<>();
    private final Object logLock = new Object();

    public ViolationManager(GuardPlugin plugin) {
        this.plugin = plugin;
        startDecay();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public boolean enabled(String check) {
        return cfg().getBoolean("checks." + check + ".enabled", true);
    }

    public boolean toggleAlerts(Player p) {
        if (alertsOff.remove(p.getUniqueId())) return true;
        alertsOff.add(p.getUniqueId());
        return false;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public void flag(Player p, String check, double add, String info) {
        PlayerData d = plugin.data(p);
        double vl = d.vl.merge(check, add, Double::sum);
        double max = cfg().getDouble("checks." + check + ".max-vl", 10);
        long now = System.currentTimeMillis();

        long cd = cfg().getLong("alerts.cooldown-ms", 750);
        Long last = d.lastAlert.get(check);
        boolean show = last == null || now - last >= cd || vl >= max;
        if (show) {
            d.lastAlert.put(check, now);
            String raw = cfg().getString("messages.alert",
                    "&f%player% &7failed &c%check% &8(&7%info%&8) &7VL &c%vl%&7/&c%max% &8| &7%ping%ms");
            String msg = raw.replace("%player%", p.getName())
                    .replace("%check%", check)
                    .replace("%info%", info)
                    .replace("%vl%", Util.f(vl))
                    .replace("%max%", String.valueOf((int) max))
                    .replace("%ping%", String.valueOf(p.getPing()));
            String full = color(cfg().getString("prefix", "&8[&bGuardian&8] ")) + color(msg);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("guardian.alerts") && !alertsOff.contains(staff.getUniqueId())) {
                    staff.sendMessage(full);
                }
            }
            plugin.getLogger().info(ChatColor.stripColor(full));
            logFile("[" + LocalDateTime.now() + "] " + p.getName() + " " + check + " " + info
                    + " VL=" + Util.f(vl) + " ping=" + p.getPing());
        }

        if (vl >= max) {
            d.vl.put(check, 0.0);
            punish(p, check, vl);
        }
    }

    private void punish(Player p, String check, double vl) {
        if (!cfg().getBoolean("punishments.enabled", true)) return;
        List<String> cmds = cfg().isList("checks." + check + ".commands")
                ? cfg().getStringList("checks." + check + ".commands")
                : cfg().getStringList("punishments.commands");
        for (String c : cmds) {
            String cmd = color(c.replace("%player%", p.getName())
                    .replace("%check%", check)
                    .replace("%vl%", Util.f(vl)));
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Exception ex) {
                plugin.getLogger().warning("Punishment command failed: " + cmd);
            }
        }
        logFile("[" + LocalDateTime.now() + "] PUNISHED " + p.getName() + " for " + check);
    }

    private void logFile(String line) {
        if (!cfg().getBoolean("alerts.log-to-file", true)) return;
        File f = new File(plugin.getDataFolder(), "violations.log");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (logLock) {
                try (FileWriter w = new FileWriter(f, true)) {
                    w.write(line + System.lineSeparator());
                } catch (IOException ignored) {
                }
            }
        });
    }

    private void startDecay() {
        long interval = Math.max(5, cfg().getLong("decay.interval-seconds", 30)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double amount = cfg().getDouble("decay.amount", 1.0);
            for (PlayerData d : plugin.allData()) {
                Iterator<Map.Entry<String, Double>> it = d.vl.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Double> en = it.next();
                    double nv = en.getValue() - amount;
                    if (nv <= 0) it.remove();
                    else en.setValue(nv);
                }
            }
        }, interval, interval);
    }
}
