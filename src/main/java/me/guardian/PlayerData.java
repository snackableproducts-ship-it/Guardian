package me.guardian;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player state used by the checks. */
public class PlayerData {
    public final UUID uuid;
    public final long joinTime = System.currentTimeMillis();

    public final Map<String, Double> vl = new HashMap<>();
    public final Map<String, Long> lastAlert = new HashMap<>();

    // timing / exemptions
    public long lastTeleport, lastVelocity, lastSetback, lastSpecialTime, lastMoveTime;
    public Location lastSafe;

    // movement
    public int airTicks;
    public boolean wasOnGround;
    public double lastDeltaY;
    public double speedBuffer, flyBuffer, timerBalance;
    public int fakeGroundTicks, waterWalkTicks, hoverSamples;
    public double hoverStartY;
    public final ArrayDeque<float[]> looks = new ArrayDeque<>();

    // combat
    public final ArrayDeque<Long> clicks = new ArrayDeque<>();
    public final ArrayDeque<Long> intervals = new ArrayDeque<>();
    public long lastClick;
    public final Map<Integer, Long> recentTargets = new HashMap<>();

    // world
    public final ArrayDeque<Long> breaks = new ArrayDeque<>();
    public final ArrayDeque<Long> places = new ArrayDeque<>();
    public double scaffoldBuffer;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }
}
