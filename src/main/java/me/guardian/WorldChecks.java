package me.guardian;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;

/** BlockReach, BlockWall (x-ray style breaking), Nuker, FastPlace, Scaffold. */
public class WorldChecks implements Listener {
    private final GuardPlugin plugin;
    private final ViolationManager V;

    public WorldChecks(GuardPlugin plugin) {
        this.plugin = plugin;
        this.V = plugin.violations();
    }

    private boolean skip(Player p) {
        GameMode gm = p.getGameMode();
        return Util.canBypass(p) || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    private int countRecent(ArrayDeque<Long> q, long now) {
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > 1000) q.removeFirst();
        return q.size();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();
        Block b = e.getBlock();
        Location eye = p.getEyeLocation();
        Vector eyeV = eye.toVector();

        // Nuker (too many blocks per second)
        if (V.enabled("nuker")) {
            int n = countRecent(d.breaks, now);
            int max = (int) plugin.cfg("checks.nuker.max-per-second", 12);
            if (n > max) {
                V.flag(p, "nuker", 1.0, n + " blocks/s");
                e.setCancelled(true);
                return;
            }
        }

        // Block reach
        if (V.enabled("blockreach")) {
            BoundingBox box = BoundingBox.of(b);
            double dist = eyeV.distance(Util.closestPoint(eyeV, box));
            double max = plugin.cfg("checks.blockreach.max-distance", 5.6);
            if (dist > max) {
                V.flag(p, "blockreach", 1.0, Util.f(dist) + " blocks");
                e.setCancelled(true);
                return;
            }
        }

        // Breaking blocks that are completely hidden behind other blocks
        if (V.enabled("blockwall") && !visible(p.getWorld(), eye, b)) {
            V.flag(p, "blockwall", 1.0, "broke hidden " + b.getType().name().toLowerCase());
            e.setCancelled(true);
        }
    }

    /** Block counts as visible if the centre or any corner can be reached by a ray from the eye. */
    private boolean visible(World w, Location eye, Block b) {
        Vector eyeV = eye.toVector();
        double[] o = {0.05, 0.5, 0.95};
        for (double ox : o) {
            for (double oy : o) {
                for (double oz : o) {
                    if (ox == 0.5 && (oy != 0.5 || oz != 0.5)) continue; // centre + corners only
                    if (ox != 0.5 && (oy == 0.5 || oz == 0.5)) continue;
                    Vector pt = new Vector(b.getX() + ox, b.getY() + oy, b.getZ() + oz);
                    Vector dir = pt.clone().subtract(eyeV);
                    double len = dir.length();
                    if (len < 0.01) return true;
                    dir.normalize();
                    RayTraceResult r = w.rayTraceBlocks(eye, dir, len, FluidCollisionMode.NEVER, true);
                    if (r == null || r.getHitBlock() == null || r.getHitBlock().equals(b)) return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();

        // FastPlace
        if (V.enabled("fastplace")) {
            int n = countRecent(d.places, now);
            int max = (int) plugin.cfg("checks.fastplace.max-per-second", 9);
            if (n > max) {
                V.flag(p, "fastplace", 1.0, n + " blocks/s");
                e.setCancelled(true);
                return;
            }
        }

        // Scaffold: block placed against a block the player isn't looking at.
        // Compares against the last few rotations because use-packets can arrive before the rotation packet.
        if (V.enabled("scaffold") && !plugin.isLagging()) {
            Block against = e.getBlockAgainst();
            if (against.getType().isSolid() && !against.isLiquid()
                    && e.getBlockReplacedState().getType().isAir()) {
                Location eye = p.getEyeLocation();
                boolean ok = rayHits(p, eye, against);
                if (!ok) {
                    for (float[] look : d.looks) {
                        Location tmp = eye.clone();
                        tmp.setYaw(look[0]);
                        tmp.setPitch(look[1]);
                        if (rayHits(p, tmp, against)) { ok = true; break; }
                    }
                }
                if (ok) {
                    d.scaffoldBuffer = Math.max(0, d.scaffoldBuffer - 0.5);
                } else {
                    d.scaffoldBuffer += 1;
                    if (d.scaffoldBuffer >= 3) {
                        V.flag(p, "scaffold", 1.0, "placed against unseen face");
                        d.scaffoldBuffer = 1;
                        e.setCancelled(true);
                    }
                }
            }
        }
    }

    private boolean rayHits(Player p, Location eye, Block against) {
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), 6.5, FluidCollisionMode.NEVER, false);
        return r != null && against.equals(r.getHitBlock());
    }
}
