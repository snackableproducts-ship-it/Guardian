package me.guardian;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.potion.PotionEffect;

/** Fly, Speed, NoFall, Jesus, Step, Phase, Timer. */
public class MovementChecks implements Listener {
    private final GuardPlugin plugin;
    private final ViolationManager V;

    public MovementChecks(GuardPlugin plugin) {
        this.plugin = plugin;
        this.V = plugin.violations();
        // Detects hovering players (no move events are fired for a player standing still in the air)
        Bukkit.getScheduler().runTaskTimer(plugin, this::hoverTick, 40L, 10L);
    }

    // ------------------------------------------------------------ state tracking

    private void touch(Player p) {
        PlayerData d = plugin.data(p);
        d.lastTeleport = System.currentTimeMillis();
        resetState(d);
    }

    private void resetState(PlayerData d) {
        d.airTicks = 0;
        d.speedBuffer = 0;
        d.flyBuffer = 0;
        d.timerBalance = 0;
        d.fakeGroundTicks = 0;
        d.waterWalkTicks = 0;
        d.hoverSamples = 0;
        d.lastDeltaY = 0;
        d.lastMoveTime = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        long now = System.currentTimeMillis();
        resetState(d);
        if (now - d.lastSetback < 300) return; // our own setback, don't grant an exemption window
        d.lastTeleport = now;
        d.lastSafe = e.getTo() == null ? null : e.getTo().clone();
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent e) { touch(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { touch(e.getPlayer()); }
    @EventHandler public void onGm(PlayerGameModeChangeEvent e) { touch(e.getPlayer()); }
    @EventHandler public void onFlightToggle(PlayerToggleFlightEvent e) { touch(e.getPlayer()); }
    @EventHandler public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) touch(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent e) {
        plugin.data(e.getPlayer()).lastVelocity = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        switch (e.getCause()) {
            case ENTITY_ATTACK:
            case ENTITY_SWEEP_ATTACK:
            case PROJECTILE:
            case BLOCK_EXPLOSION:
            case ENTITY_EXPLOSION:
                plugin.data(p).lastVelocity = System.currentTimeMillis();
                break;
            default:
                if (e.getCause().name().equals("WIND_BURST")) plugin.data(p).lastVelocity = System.currentTimeMillis();
        }
    }

    private boolean exempt(Player p, PlayerData d, long now) {
        if (Util.canBypass(p)) return true;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isInsideVehicle()
                || p.isRiptiding() || p.isDead() || p.isSleeping()) return true;
        if (now - d.lastTeleport < 1500 || now - d.lastVelocity < 1800 || now - d.joinTime < 3000) return true;
        return plugin.isLagging();
    }

    private void setback(PlayerMoveEvent e, PlayerData d, boolean toSafe) {
        d.lastSetback = System.currentTimeMillis();
        Location target = e.getFrom();
        Location safe = d.lastSafe;
        if (toSafe && safe != null && safe.getWorld() != null
                && safe.getWorld().equals(e.getFrom().getWorld())
                && safe.distanceSquared(e.getFrom()) < 64 * 64) {
            target = safe;
        }
        e.setTo(target.clone());
        d.airTicks = 0;
        d.lastDeltaY = 0;
        d.speedBuffer = 0;
        d.flyBuffer = 0;
    }

    // ------------------------------------------------------------ main move handler

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;

        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();

        d.looks.addLast(new float[]{to.getYaw(), to.getPitch()});
        while (d.looks.size() > 5) d.looks.removeFirst();

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        long gap = now - d.lastMoveTime;
        boolean consecutive = d.lastMoveTime != 0 && gap <= 75;

        if (exempt(p, d, now)) {
            resetState(d);
            d.lastDeltaY = dy;
            d.lastMoveTime = now;
            return;
        }

        boolean onGround = Util.nearGround(to);
        boolean special = Util.specialAt(to);
        if (special) d.lastSpecialTime = now;
        boolean recentSpecial = now - d.lastSpecialTime < 600;
        boolean flagged = false;
        boolean toSafe = false;

        // ---------------- Timer (client sending moves faster than 20/s)
        if (V.enabled("timer") && d.lastMoveTime != 0) {
            double credit = plugin.cfg("checks.timer.max-credit-ms", 1500);
            double limit = plugin.cfg("checks.timer.threshold-ms", 250);
            d.timerBalance += 50 - gap;
            if (d.timerBalance < -credit) d.timerBalance = -credit;
            if (d.timerBalance > limit) {
                V.flag(p, "timer", 1.0, "balance=" + (int) d.timerBalance + "ms");
                d.timerBalance = 0;
                flagged = true;
            }
        }

        // ---------------- Phase (moving into solid blocks)
        if (V.enabled("phase") && h > 0.1) {
            Block feet = to.getBlock();
            Block head = to.clone().add(0, 1, 0).getBlock();
            if (feet.getType().isOccluding() && head.getType().isOccluding()
                    && !from.getBlock().getType().isOccluding()) {
                V.flag(p, "phase", 2.0, "moved into " + feet.getType().name().toLowerCase());
                flagged = true;
                toSafe = true;
            }
        }

        // ---------------- Speed
        if (V.enabled("speed")) {
            double limit = (onGround && d.wasOnGround)
                    ? plugin.cfg("checks.speed.max-ground", 0.38)
                    : plugin.cfg("checks.speed.max-air", 0.68);
            PotionEffect fx = Util.effect(p, "speed");
            if (fx != null) limit *= 1.0 + 0.2 * (fx.getAmplifier() + 1);
            limit *= Math.max(1.0, p.getWalkSpeed() / 0.2);
            if (Util.nearIce(to)) limit *= 1.9;
            if (special) limit *= 1.6;

            if (h > limit) d.speedBuffer += 1.0 + (h / limit - 1.0) * 2.0;
            else d.speedBuffer = Math.max(0, d.speedBuffer - 0.35);

            if (d.speedBuffer >= plugin.cfg("checks.speed.buffer", 5)) {
                V.flag(p, "speed", 1.0, Util.f(h) + " b/t, limit " + Util.f(limit));
                d.speedBuffer = 2;
                flagged = true;
            }
        }

        // ---------------- Step (instant climbing)
        if (V.enabled("step") && consecutive && onGround && !special && !recentSpecial
                && dy > 0.7 && Util.effect(p, "jump_boost") == null) {
            V.flag(p, "step", 1.0, "dy=" + Util.f(dy));
            flagged = true;
            toSafe = true;
        }

        // ---------------- Fly (vertical prediction)
        if (onGround || special) {
            d.airTicks = 0;
            d.flyBuffer = Math.max(0, d.flyBuffer - 0.5);
        } else {
            d.airTicks++;
            boolean flyActive = V.enabled("fly") && !recentSpecial
                    && Util.effect(p, "levitation") == null
                    && Util.effect(p, "slow_falling") == null;
            if (flyActive && d.airTicks >= 3 && consecutive) {
                double expected = (d.lastDeltaY - 0.08) * 0.98;
                double diff = dy - expected;
                double tol = plugin.cfg("checks.fly.tolerance", 0.10);
                if (h < 0.03) tol *= 2;
                if (diff > tol && !Util.standingOnEntity(p)) {
                    d.flyBuffer += 1;
                    if (d.flyBuffer >= 2) {
                        V.flag(p, "fly", 1.0, "dy=" + Util.f(dy) + " expected=" + Util.f(expected));
                        d.flyBuffer = 1;
                        flagged = true;
                        toSafe = true;
                    }
                } else {
                    d.flyBuffer = Math.max(0, d.flyBuffer - 0.5);
                }
            }
        }

        // ---------------- NoFall (claims to be on ground while falling in mid-air)
        if (V.enabled("nofall")) {
            if (p.isOnGround() && dy < -0.2 && !special && !recentSpecial && Util.airBelow(to, 1.6)) {
                d.fakeGroundTicks++;
                if (d.fakeGroundTicks >= 3) {
                    V.flag(p, "nofall", 1.0, "spoofed onGround, dy=" + Util.f(dy));
                    d.fakeGroundTicks = 0;
                    flagged = true;
                    toSafe = true;
                }
            } else {
                d.fakeGroundTicks = 0;
            }
        }

        // ---------------- Jesus (walking on water)
        if (V.enabled("jesus")) {
            Block feet = to.getBlock();
            Block under = Util.at(to.getWorld(), to.getX(), to.getY() - 0.3, to.getZ());
            boolean water = feet.getType() == Material.WATER || under.getType() == Material.WATER;
            if (water && !onGround && Math.abs(dy) < 0.0005 && h > 0.1 && !p.isSwimming()) {
                d.waterWalkTicks++;
            } else {
                d.waterWalkTicks = 0;
            }
            if (d.waterWalkTicks >= 12) {
                d.waterWalkTicks = 0;
                if (!Util.standingOnEntity(p)) {
                    V.flag(p, "jesus", 1.0, "walking on water");
                    flagged = true;
                }
            }
        }

        // ---------------- finish
        if (flagged) {
            setback(e, d, toSafe);
        } else {
            if (onGround && !special) d.lastSafe = to.clone();
            d.lastDeltaY = dy;
        }
        d.wasOnGround = onGround;
        d.lastMoveTime = now;
    }

    // ------------------------------------------------------------ hover detection

    private void hoverTick() {
        if (!V.enabled("fly")) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data(p);
            if (exempt(p, d, now) || now - d.lastVelocity < 4500) {
                d.hoverSamples = 0;
                continue;
            }
            Location loc = p.getLocation();
            if (Util.nearGround(loc) || Util.specialAt(loc) || now - d.lastSpecialTime < 600
                    || Util.effect(p, "levitation") != null || Util.effect(p, "slow_falling") != null
                    || Util.standingOnEntity(p)) {
                d.hoverSamples = 0;
                continue;
            }
            if (d.hoverSamples == 0) d.hoverStartY = loc.getY();
            d.hoverSamples++;
            // ~5 samples = ~2 seconds continuously airborne without dropping
            if (d.hoverSamples >= 5 && d.hoverStartY - loc.getY() < 1.5) {
                V.flag(p, "fly", 2.0, "hovering in air");
                d.hoverSamples = 0;
                Location safe = d.lastSafe;
                if (safe != null && safe.getWorld() != null && safe.getWorld().equals(loc.getWorld())) {
                    d.lastSetback = now;
                    p.teleport(safe);
                }
            }
        }
    }
}
