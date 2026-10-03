package me.guardian;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Reach, KillAura (multi-target / angle), hit-through-walls, AutoClicker. */
public class CombatChecks implements Listener {
    private final GuardPlugin plugin;
    private final ViolationManager V;

    public CombatChecks(GuardPlugin plugin) {
        this.plugin = plugin;
        this.V = plugin.violations();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (Util.canBypass(p)) return;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;

        Entity target = e.getEntity();
        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();

        Location eye = p.getEyeLocation();
        Vector eyeV = eye.toVector();
        BoundingBox box = target.getBoundingBox();
        Vector closest = Util.closestPoint(eyeV, box);
        double dist = eyeV.distance(closest);
        int ping = p.getPing();

        // ---------------- Reach
        if (V.enabled("reach")) {
            double max = plugin.cfg("checks.reach.max-distance", 3.0)
                    + plugin.cfg("checks.reach.leeway", 0.45)
                    + Math.min(ping, 400) / 1000.0 * 6.0;
            if (dist > max) {
                V.flag(p, "reach", 1.0 + (dist - max), Util.f(dist) + " blocks (max " + Util.f(max) + ")");
                e.setCancelled(true);
                return;
            }
        }

        // ---------------- Hit through walls
        if (V.enabled("hitwall") && dist > 0.8) {
            Vector dir = closest.clone().subtract(eyeV);
            double len = dir.length();
            if (len > 0.01) {
                dir.normalize();
                RayTraceResult r = p.getWorld().rayTraceBlocks(eye, dir, len, FluidCollisionMode.NEVER, true);
                if (r != null && r.getHitBlock() != null
                        && r.getHitPosition().distance(eyeV) < len - 0.35) {
                    V.flag(p, "hitwall", 1.0, "hit through " + r.getHitBlock().getType().name().toLowerCase());
                    e.setCancelled(true);
                    return;
                }
            }
        }

        // ---------------- KillAura
        if (V.enabled("killaura")) {
            final long t = now;
            d.recentTargets.put(target.getEntityId(), now);
            d.recentTargets.values().removeIf(v -> t - v > 600);
            int maxTargets = (int) plugin.cfg("checks.killaura.max-targets", 3);
            if (d.recentTargets.size() >= maxTargets) {
                V.flag(p, "killaura", 1.5, d.recentTargets.size() + " targets in 600ms");
                d.recentTargets.clear();
                e.setCancelled(true);
                return;
            }

            if (dist > 1.2 && box.getWidthX() < 2.0 && box.getWidthZ() < 2.0 && box.getHeight() < 3.5) {
                Vector toCenter = box.getCenter().subtract(eyeV);
                if (toCenter.lengthSquared() > 0.01) {
                    double angle = Math.toDegrees(eye.getDirection().angle(toCenter));
                    double maxAngle = plugin.cfg("checks.killaura.max-angle", 85);
                    if (angle > maxAngle) {
                        V.flag(p, "killaura", 1.0, "angle " + (int) angle + "°");
                        e.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (Util.canBypass(p) || !V.enabled("autoclicker")) return;

        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();

        if (plugin.isLagging()) {
            d.intervals.clear();
            d.clicks.clear();
            d.lastClick = now;
            return;
        }

        // CPS window
        d.clicks.addLast(now);
        while (!d.clicks.isEmpty() && now - d.clicks.peekFirst() > 1000) d.clicks.removeFirst();
        int cps = d.clicks.size();
        int maxCps = (int) plugin.cfg("checks.autoclicker.max-cps", 22);
        if (cps > maxCps) {
            V.flag(p, "autoclicker", 1.0, cps + " CPS (max " + maxCps + ")");
            d.clicks.clear();
        }

        // Click consistency (robotic timing). Skipped with haste, since mining swings are very regular.
        boolean mining = Util.effect(p, "haste") != null || Util.effect(p, "conduit_power") != null;
        if (d.lastClick != 0 && !mining) {
            long iv = now - d.lastClick;
            if (iv < 400) {
                d.intervals.addLast(iv);
                if (d.intervals.size() > 30) d.intervals.removeFirst();
            } else {
                d.intervals.clear();
            }
            if (d.intervals.size() >= 30) {
                double sum = 0;
                for (long v : d.intervals) sum += v;
                double avg = sum / d.intervals.size();
                double var = 0;
                for (long v : d.intervals) var += (v - avg) * (v - avg);
                double std = Math.sqrt(var / d.intervals.size());
                if (avg < 125 && std < plugin.cfg("checks.autoclicker.min-std-ms", 8)) {
                    V.flag(p, "autoclicker", 2.0, "robotic clicks, avg " + (int) avg + "ms, std " + Util.f(std));
                }
                d.intervals.clear();
            }
        } else if (mining) {
            d.intervals.clear();
        }
        d.lastClick = now;
    }
}
