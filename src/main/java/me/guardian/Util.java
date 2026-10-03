package me.guardian;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Vehicle;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Locale;

final class Util {
    private Util() {}

    private static final double[] OFF = {-0.31, 0.0, 0.31};

    static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static boolean canBypass(Player p) {
        return p.hasPermission("guardian.bypass");
    }

    static Block at(World w, double x, double y, double z) {
        return w.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    static Vector closestPoint(Vector p, BoundingBox b) {
        return new Vector(
                clamp(p.getX(), b.getMinX(), b.getMaxX()),
                clamp(p.getY(), b.getMinY(), b.getMaxY()),
                clamp(p.getZ(), b.getMinZ(), b.getMaxZ()));
    }

    /** True if a solid block supports the player (generous tolerance, avoids false positives). */
    static boolean nearGround(Location l) {
        World w = l.getWorld();
        if (w == null) return false;
        double[] ys = {0.05, 0.3, 0.55};
        for (double dx : OFF) {
            for (double dz : OFF) {
                for (double dy : ys) {
                    if (!at(w, l.getX() + dx, l.getY() - dy, l.getZ() + dz).isPassable()) return true;
                }
            }
        }
        return false;
    }

    /** True if there is only air/passable non-liquid space below the player down to 'depth'. */
    static boolean airBelow(Location l, double depth) {
        World w = l.getWorld();
        if (w == null) return false;
        double[] ys = {0.05, 0.5, 1.0, depth};
        for (double dx : OFF) {
            for (double dz : OFF) {
                for (double dy : ys) {
                    Block b = at(w, l.getX() + dx, l.getY() - dy, l.getZ() + dz);
                    if (!b.isPassable() || b.isLiquid()) return false;
                }
            }
        }
        return true;
    }

    static boolean isSpecial(Block b) {
        Material m = b.getType();
        if (m.isAir()) return false;
        if (b.isLiquid()) return true;
        if (Tag.CLIMBABLE.isTagged(m) || Tag.BEDS.isTagged(m)) return true;
        String n = m.name();
        return n.contains("COBWEB") || n.equals("HONEY_BLOCK") || n.equals("SLIME_BLOCK")
                || n.equals("POWDER_SNOW") || n.equals("BUBBLE_COLUMN") || n.equals("SWEET_BERRY_BUSH");
    }

    /** Liquids, ladders, vines, cobwebs, slime, honey, beds, ... around the player's body. */
    static boolean specialAt(Location l) {
        World w = l.getWorld();
        if (w == null) return false;
        double[] ys = {-0.1, 0.0, 0.9, 1.8};
        for (double dx : OFF) {
            for (double dz : OFF) {
                for (double dy : ys) {
                    if (isSpecial(at(w, l.getX() + dx, l.getY() + dy, l.getZ() + dz))) return true;
                }
            }
        }
        return false;
    }

    static boolean nearIce(Location l) {
        World w = l.getWorld();
        if (w == null) return false;
        for (double dy : new double[]{0.1, 0.6, 1.1}) {
            if (at(w, l.getX(), l.getY() - dy, l.getZ()).getType().name().endsWith("ICE")) return true;
        }
        return false;
    }

    static PotionEffect effect(Player p, String key) {
        for (PotionEffect pe : p.getActivePotionEffects()) {
            if (pe.getType().getKey().getKey().equals(key)) return pe;
        }
        return null;
    }

    static boolean standingOnEntity(Player p) {
        for (Entity e : p.getNearbyEntities(0.8, 1.2, 0.8)) {
            if (e instanceof Vehicle || e instanceof Shulker) return true;
        }
        return false;
    }
}
