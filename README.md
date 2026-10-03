# Guardian – Spigot anticheat (1.21.11)

Checks: Fly (prediction + hover), Speed, NoFall, Jesus, Step, Phase, Timer,
Reach, KillAura (multi-target + angle), Hit-through-walls, AutoClicker (CPS + robotic timing),
BlockReach, BlockWall (x-ray style breaking), Nuker, FastPlace, Scaffold.

Also: violation levels with decay, staff alerts, setbacks, TPS/lag protection (important on Aternos),
configurable punishments, `violations.log`.

## Getting the .jar (Aternos only accepts compiled jars)
**Option A – GitHub (no installs):**
1. Create a free GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the repo's **Actions** tab -> "Build Guardian" -> wait ~1 minute.
3. Download the `Guardian-jar` artifact, unzip it -> `Guardian-1.0.0.jar`.

**Option B – locally:** install JDK 21 + Maven, then run `mvn package`. The jar is in `target/`.

## Installing on Aternos
1. Server software: **Spigot 1.21.11**, Java 21.
2. Files tab -> `plugins` folder -> upload `Guardian-1.0.0.jar`. Restart.
3. Edit `plugins/Guardian/config.yml` in the Files tab if you want (e.g. use `ban` instead of `kick`).

## Commands
`/gac alerts` toggle alerts · `/gac status` · `/gac info <player>` · `/gac reset <player>` · `/gac reload`

## Permissions
`guardian.admin` (op), `guardian.alerts` (op), `guardian.bypass` (nobody by default).
Test with a non-op survival account, because creative/spectator/flight are exempt.

## Notes
- Disable `nuker` if you use vein-miner / tree-chopper plugins.
- Start with `punishments.commands` set to kick, watch alerts for a few days, then switch to ban.
