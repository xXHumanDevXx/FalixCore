package de.falixcore;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Modul AntiCheat: Speed, Fly, Reach, KillAura und AutoClicker mit Verstoß-Level (VL). */
public class AntiCheatModule implements Module, Listener {

    private static final class Data {
        int airTicks;
        double airStartY;
        long lastTeleport;
        long lastVelocity;
        UUID lastTarget;
        long lastHit;
        long lastCpsFlag;
        final Deque<Long> swings = new ArrayDeque<>();
    }

    private final FalixCore core;
    private final Map<UUID, Map<String, Double>> levels = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> kickStrikes = new ConcurrentHashMap<>();
    private final Map<UUID, Data> data = new ConcurrentHashMap<>();
    private int secondsCounter = 0;

    public AntiCheatModule(FalixCore core) {
        this.core = core;
    }

    @Override
    public String name() {
        return "anticheat";
    }

    @Override
    public List<String> commands() {
        return new ArrayList<>();
    }

    @Override
    public void enable() {
        core.getServer().getPluginManager().registerEvents(this, core);
        // Jede Sekunde prüfen, ob VL sinkt (decay)
        Bukkit.getScheduler().runTaskTimer(core, this::decayTick, 20L, 20L);
    }

    @Override
    public void disable() {
        levels.clear();
    }

    @Override
    public void reload() {
        // Config wird bei jedem Zugriff frisch gelesen
    }

    @Override
    public boolean handle(CommandSender s, String command, String[] a) {
        return false;
    }

    @Override
    public List<String> complete(CommandSender s, String command, String[] a) {
        return new ArrayList<>();
    }

    private YamlConfiguration cfg() {
        return core.cfg("anticheat");
    }

    private double cfgD(String path, double def) {
        return cfg().getDouble(path, def);
    }

    private Data d(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new Data());
    }

    // ------------------------------------------------------------------
    //  VL, Kick und Ban
    // ------------------------------------------------------------------

    private void decayTick() {
        int interval = Math.max(1, cfg().getInt("anticheat.violation-decay.interval-seconds", 10));
        if (++secondsCounter < interval) return;
        secondsCounter = 0;
        double amount = cfg().getDouble("anticheat.violation-decay.amount", 1.0);
        levels.values().forEach(map -> map.replaceAll((check, vl) -> vl - amount));
        levels.values().forEach(map -> map.values().removeIf(vl -> vl <= 0));
    }

    /** Erhöht das VL eines Checks und löst bei Bedarf Kick oder Ban aus. */
    private void flag(Player p, String check, String info) {
        String path = "checks." + check + ".";
        if (!cfg().getBoolean(path + "enabled", true)) return;
        if (p.hasPermission("falixcore.bypass")) return;
        if (p.getPing() > cfg().getInt("settings.ignore-ping-above", 500)) return;

        double vl = levels.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>())
                .merge(check, cfg().getDouble(path + "vl-per-flag", 1.0), Double::sum);
        double kickAt = cfg().getDouble(path + "kick-at", 0);
        double banAt = cfg().getDouble(path + "ban-at", 0);
        String vlText = String.format("%.1f", vl);

        if (cfg().getBoolean("settings.log-flags", true)) {
            core.getLogger().info("[AntiCheat] " + p.getName() + " verdächtig: " + check
                    + " (VL " + vlText + " | Kick ab " + kickAt + " | Ban ab " + banAt + ") " + info);
        }
        if (cfg().getBoolean("settings.alerts-to-staff", true)) {
            String alert = Ui.msg(core, cfg(), "alert", "{player}", p.getName(), "{check}", check,
                    "{vl}", vlText, "{info}", info);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("falixcore.alerts")) staff.sendMessage(alert);
            }
        }

        if (banAt > 0 && vl >= banAt) {
            run("ban", p, check);
        } else if (kickAt > 0 && vl >= kickAt) {
            int strikes = kickStrikes.merge(p.getUniqueId(), 1, Integer::sum);
            int kicksBeforeBan = cfg().getInt("anticheat.kicks-before-ban", 2);
            if (kicksBeforeBan > 0 && strikes > kicksBeforeBan) {
                run("ban", p, check);
            } else {
                run("kick", p, check);
            }
        }
    }

    private void run(String action, Player p, String check) {
        levels.remove(p.getUniqueId());
        String cmd = cfg().getString("anticheat.actions." + action);
        if (cmd == null || cmd.isBlank()) return;
        cmd = cmd.replace("{player}", p.getName()).replace("{check}", check);
        core.getLogger().info("[AntiCheat] Aktion " + action.toUpperCase() + " gegen " + p.getName()
                + " (" + check + ")");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    // ------------------------------------------------------------------
    //  Zustand verfolgen
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        d(e.getPlayer()).lastTeleport = System.currentTimeMillis();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
        levels.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        Data x = d(e.getPlayer());
        x.lastTeleport = System.currentTimeMillis();
        x.airTicks = 0;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        d(e.getPlayer()).lastTeleport = System.currentTimeMillis();
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        d(e.getPlayer()).lastVelocity = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    //  Bewegung: Speed und Fly
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        Player p = e.getPlayer();
        Data x = d(p);
        GameMode gm = p.getGameMode();

        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.isDead()
                || p.getAllowFlight() || p.isFlying() || p.isInsideVehicle()
                || p.isGliding() || p.isRiptiding()) {
            x.airTicks = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - x.lastTeleport < 2000 || now - x.lastVelocity < 2500) {
            x.airTicks = 0;
            x.airStartY = to.getY();
            return;
        }
        checkSpeed(p, from, to);
        checkFly(p, x, from, to);
    }

    private void checkSpeed(Player p, Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        double max = cfgD("checks.speed.max-per-move", 0.9);
        int speed = amplifier(p, "SPEED");
        if (speed >= 0) max *= 1.0 + 0.25 * (speed + 1);
        max *= p.getWalkSpeed() / 0.2;
        if (to.clone().subtract(0, 0.5, 0).getBlock().getType().name().contains("ICE")) max *= 1.8;

        if (horizontal > max) {
            flag(p, "speed", String.format("%.2f Blöcke/Tick (erlaubt %.2f)", horizontal, max));
        }
    }

    private void checkFly(Player p, Data x, Location from, Location to) {
        boolean safe = nearGround(to)
                || to.getBlock().isLiquid()
                || to.clone().subtract(0, 0.2, 0).getBlock().isLiquid()
                || Tag.CLIMBABLE.isTagged(to.getBlock().getType())
                || to.getBlock().getType() == Material.COBWEB
                || amplifier(p, "LEVITATION") >= 0
                || amplifier(p, "SLOW_FALLING") >= 0;
        if (safe) {
            x.airTicks = 0;
            x.airStartY = to.getY();
            return;
        }
        if (x.airTicks == 0) x.airStartY = from.getY();
        x.airTicks++;

        double dy = to.getY() - from.getY();
        int jump = amplifier(p, "JUMP_BOOST", "JUMP");
        double maxRise = cfgD("checks.fly.max-rise", 2.2) + (jump >= 0 ? 0.6 * (jump + 1) : 0);
        int hoverTicks = (int) cfgD("checks.fly.hover-ticks", 25);

        String problem = null;
        if (to.getY() - x.airStartY > maxRise) {
            problem = String.format("steigt in der Luft um %.2f Blöcke", to.getY() - x.airStartY);
        } else if (x.airTicks > hoverTicks && Math.abs(dy) < 0.02) {
            problem = "schwebt in der Luft (" + x.airTicks + " Ticks)";
        }
        if (problem == null) return;
        if (supportedByEntity(p)) {
            x.airTicks = 0;
            return;
        }
        flag(p, "fly", problem);
    }

    private boolean nearGround(Location l) {
        World w = l.getWorld();
        double[] offsets = {-0.31, 0, 0.31};
        double[] heights = {-0.1, -0.6};
        for (double dx : offsets) {
            for (double dz : offsets) {
                for (double dy : heights) {
                    Block b = w.getBlockAt((int) Math.floor(l.getX() + dx),
                            (int) Math.floor(l.getY() + dy),
                            (int) Math.floor(l.getZ() + dz));
                    if (b.getType().isSolid()) return true;
                }
            }
        }
        return false;
    }

    private boolean supportedByEntity(Player p) {
        for (Entity en : p.getNearbyEntities(1.5, 1.5, 1.5)) {
            if (en instanceof Boat || en instanceof Shulker || en instanceof Minecart) return true;
        }
        return false;
    }

    private int amplifier(Player p, String... names) {
        for (String n : names) {
            PotionEffectType type = PotionEffectType.getByName(n);
            if (type == null) continue;
            PotionEffect eff = p.getPotionEffect(type);
            if (eff != null) return eff.getAmplifier();
        }
        return -1;
    }

    // ------------------------------------------------------------------
    //  Kampf: Reach und KillAura
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;

        Entity target = e.getEntity();
        Data x = d(p);
        long now = System.currentTimeMillis();

        BoundingBox box = target.getBoundingBox();
        Vector eye = p.getEyeLocation().toVector();
        Vector closest = new Vector(
                clamp(eye.getX(), box.getMinX(), box.getMaxX()),
                clamp(eye.getY(), box.getMinY(), box.getMaxY()),
                clamp(eye.getZ(), box.getMinZ(), box.getMaxZ()));
        double reach = eye.distance(closest);
        double maxReach = cfgD("checks.reach.max-distance", 3.6);
        if (reach > maxReach) {
            flag(p, "reach", String.format("Reichweite %.2f (erlaubt %.2f)", reach, maxReach));
        }

        Vector toTarget = box.getCenter().subtract(eye);
        if (toTarget.length() > 1.5) {
            double angle = Math.toDegrees(p.getEyeLocation().getDirection().angle(toTarget));
            double maxAngle = cfgD("checks.killaura.max-angle", 75);
            if (angle > maxAngle) {
                flag(p, "killaura", String.format("Winkel %.0f° (erlaubt %.0f°)", angle, maxAngle));
            }
        }

        long multiMs = (long) cfgD("checks.killaura.multi-target-ms", 90);
        if (x.lastTarget != null && !x.lastTarget.equals(target.getUniqueId()) && now - x.lastHit < multiMs) {
            flag(p, "killaura", "mehrere Ziele in " + (now - x.lastHit) + " ms");
        }
        x.lastTarget = target.getUniqueId();
        x.lastHit = now;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------
    //  AutoClicker
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        Data x = d(p);
        long now = System.currentTimeMillis();
        x.swings.addLast(now);
        while (!x.swings.isEmpty() && now - x.swings.peekFirst() > 1000) x.swings.pollFirst();

        int maxCps = (int) cfgD("checks.autoclicker.max-cps", 22);
        if (x.swings.size() > maxCps && now - x.lastCpsFlag > 500) {
            x.lastCpsFlag = now;
            flag(p, "autoclicker", "CPS " + x.swings.size() + " (erlaubt " + maxCps + ")");
        }
    }
}
