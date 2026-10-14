package de.falixcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Modul Guard: Bans, Mutes, Timeouts, Kicks, Verwarnungen und Ankündigungen. */
public class GuardModule implements Module, Listener {

    public enum Type { BAN, MUTE, TIMEOUT }

    /** until = -1 bedeutet permanent. */
    public record Entry(String name, String reason, String by, long until) {
        public boolean permanent() {
            return until == -1;
        }

        public boolean expired() {
            return !permanent() && System.currentTimeMillis() > until;
        }

        public long remaining() {
            return permanent() ? -1 : Math.max(0, until - System.currentTimeMillis());
        }
    }

    private record Target(UUID id, String name, Player online) { }

    private static final String FILE = "data-guard.yml";

    private final FalixCore core;
    private final File file;
    private final Map<Type, Map<UUID, Entry>> active = new EnumMap<>(Type.class);
    private final Map<UUID, String> warnNames = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> warns = new ConcurrentHashMap<>();
    private int announceIndex = 0;

    public GuardModule(FalixCore core) {
        this.core = core;
        this.file = core.dataFile(FILE);
        for (Type t : Type.values()) active.put(t, new ConcurrentHashMap<>());
    }

    @Override
    public String name() {
        return "guard";
    }

    @Override
    public List<String> commands() {
        return List.of("ban", "unban", "kick", "mute", "unmute", "timeout", "untimeout",
                "warn", "warnings", "clearwarns", "announce");
    }

    @Override
    public void enable() {
        load();
        core.getServer().getPluginManager().registerEvents(this, core);
        long interval = cfg().getLong("announce.interval-seconds", 300L) * 20L;
        if (interval > 0) {
            Bukkit.getScheduler().runTaskTimer(core, this::autoAnnounce, interval, interval);
        }
    }

    @Override
    public void disable() {
        save();
    }

    @Override
    public void reload() {
        // Config wird bei jedem Zugriff frisch gelesen. Das Ankündigungs-Intervall gilt nach Neustart.
    }

    private YamlConfiguration cfg() {
        return core.cfg("guard");
    }

    private String msg(String key, String... pairs) {
        return Ui.msg(core, cfg(), key, pairs);
    }

    /** Zeilen aus messages.<key> (z. B. Kick-Bildschirm), mit Platzhaltern. */
    private String screen(String key, String... pairs) {
        List<String> out = new ArrayList<>();
        for (String line : cfg().getStringList("messages." + key)) {
            String raw = line;
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                raw = raw.replace(pairs[i], pairs[i + 1]);
            }
            out.add(Ui.color(raw));
        }
        return String.join("\n", out);
    }

    // ------------------------------------------------------------------
    //  Daten
    // ------------------------------------------------------------------

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Type t : Type.values()) {
            var sec = y.getConfigurationSection(t.name().toLowerCase(Locale.ROOT));
            if (sec == null) continue;
            for (String key : sec.getKeys(false)) {
                try {
                    active.get(t).put(UUID.fromString(key), new Entry(
                            sec.getString(key + ".name", "?"),
                            sec.getString(key + ".reason", ""),
                            sec.getString(key + ".by", "?"),
                            sec.getLong(key + ".until", -1L)));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        var w = y.getConfigurationSection("warns");
        if (w != null) {
            for (String key : w.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    warnNames.put(id, w.getString(key + ".name", "?"));
                    warns.put(id, new ArrayList<>(w.getStringList(key + ".list")));
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Type t : Type.values()) {
            String base = t.name().toLowerCase(Locale.ROOT) + ".";
            active.get(t).forEach((id, e) -> {
                y.set(base + id + ".name", e.name());
                y.set(base + id + ".reason", e.reason());
                y.set(base + id + ".by", e.by());
                y.set(base + id + ".until", e.until());
            });
        }
        warns.forEach((id, list) -> {
            if (list.isEmpty()) return;
            y.set("warns." + id + ".name", warnNames.getOrDefault(id, "?"));
            y.set("warns." + id + ".list", list);
        });
        try {
            y.save(file);
        } catch (IOException ex) {
            core.getLogger().severe(FILE + " konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    private void apply(Type type, UUID id, String name, String reason, String by, long durationMs) {
        long until = durationMs < 0 ? -1 : System.currentTimeMillis() + durationMs;
        active.get(type).put(id, new Entry(name, reason, by, until));
        save();
    }

    /** Aktive Strafe oder null. Abgelaufene werden entfernt. */
    private Entry get(Type type, UUID id) {
        Entry e = active.get(type).get(id);
        if (e == null) return null;
        if (e.expired()) {
            active.get(type).remove(id);
            save();
            return null;
        }
        return e;
    }

    private boolean remove(Type type, UUID id) {
        boolean removed = active.get(type).remove(id) != null;
        if (removed) save();
        return removed;
    }

    private UUID findByName(Type type, String name) {
        for (Map.Entry<UUID, Entry> en : active.get(type).entrySet()) {
            if (en.getValue().name().equalsIgnoreCase(name)) return en.getKey();
        }
        return null;
    }

    private int addWarn(UUID id, String name, String reason, String by) {
        List<String> list = warns.computeIfAbsent(id, k -> new ArrayList<>());
        list.add(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                + " | " + by + " | " + reason);
        warnNames.put(id, name);
        save();
        return list.size();
    }

    // ------------------------------------------------------------------
    //  Befehle
    // ------------------------------------------------------------------

    @Override
    public boolean handle(CommandSender s, String command, String[] a) {
        if (!s.hasPermission("falixcore.mod")) {
            s.sendMessage(msg("no-permission"));
            return true;
        }
        switch (command) {
            case "ban" -> punish(s, a, Type.BAN);
            case "mute" -> punish(s, a, Type.MUTE);
            case "timeout" -> punish(s, a, Type.TIMEOUT);
            case "unban" -> unpunish(s, a, Type.BAN);
            case "unmute" -> unpunish(s, a, Type.MUTE);
            case "untimeout" -> unpunish(s, a, Type.TIMEOUT);
            case "kick" -> kick(s, a);
            case "warn" -> warn(s, a);
            case "warnings" -> warnings(s, a);
            case "clearwarns" -> clearWarns(s, a);
            case "announce" -> announce(s, a);
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> complete(CommandSender s, String command, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            Bukkit.getOnlinePlayers().forEach(x -> out.add(x.getName()));
        }
        String start = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(start));
        return out;
    }

    private void punish(CommandSender s, String[] a, Type type) {
        String cmd = type.name().toLowerCase(Locale.ROOT);
        boolean durationRequired = type == Type.TIMEOUT;
        if (a.length < (durationRequired ? 2 : 1)) {
            s.sendMessage(msg("usage", "{usage}", "/" + cmd + " <Spieler> " + (durationRequired ? "<Dauer>" : "[Dauer]") + " [Grund]"));
            return;
        }
        Target t = target(s, a[0]);
        if (t == null) return;

        long duration = -1;
        int reasonFrom = 1;
        if (a.length > 1) {
            long parsed = TimeUtil.parse(a[1]);
            if (parsed != -2) {
                duration = parsed;
                reasonFrom = 2;
            } else if (durationRequired) {
                s.sendMessage(msg("invalid-duration"));
                return;
            }
        }
        if (durationRequired && duration <= 0) {
            s.sendMessage(msg("invalid-duration"));
            return;
        }
        if (exempt(s, t)) return;

        String reason = reason(a, reasonFrom);
        String time = TimeUtil.format(duration);
        apply(type, t.id(), t.name(), reason, s.getName(), duration);

        Player on = t.online();
        if (on != null) {
            switch (type) {
                case BAN -> on.kickPlayer(screen("ban-screen", "{reason}", reason,
                        "{duration}", time, "{by}", s.getName()));
                case MUTE -> on.sendMessage(msg("you-muted", "{reason}", reason, "{duration}", time));
                case TIMEOUT -> on.sendMessage(msg("you-timeout", "{reason}", reason, "{duration}", time));
                default -> { }
            }
        }
        broadcast(msg("punish-" + cmd, "{player}", t.name(), "{by}", s.getName(),
                "{duration}", time, "{reason}", reason));
    }

    private void unpunish(CommandSender s, String[] a, Type type) {
        String cmd = type.name().toLowerCase(Locale.ROOT);
        if (a.length < 1) {
            s.sendMessage(msg("usage", "{usage}", "/un" + cmd + " <Spieler>"));
            return;
        }
        UUID id = findByName(type, a[0]);
        String name = a[0];
        if (id == null) {
            Target t = target(s, a[0]);
            if (t == null) return;
            id = t.id();
            name = t.name();
        }
        if (!remove(type, id)) {
            s.sendMessage(msg("not-punished", "{player}", name));
            return;
        }
        broadcast(msg("punish-un" + cmd, "{player}", name, "{by}", s.getName()));
    }

    private void kick(CommandSender s, String[] a) {
        if (a.length < 1) {
            s.sendMessage(msg("usage", "{usage}", "/kick <Spieler> [Grund]"));
            return;
        }
        Player p = Bukkit.getPlayerExact(a[0]);
        if (p == null) {
            s.sendMessage(msg("player-offline", "{player}", a[0]));
            return;
        }
        Target t = new Target(p.getUniqueId(), p.getName(), p);
        if (exempt(s, t)) return;
        String reason = reason(a, 1);
        p.kickPlayer(screen("kick-screen", "{reason}", reason, "{by}", s.getName()));
        broadcast(msg("punish-kick", "{player}", t.name(), "{by}", s.getName(), "{reason}", reason));
    }

    private void warn(CommandSender s, String[] a) {
        if (a.length < 1) {
            s.sendMessage(msg("usage", "{usage}", "/warn <Spieler> [Grund]"));
            return;
        }
        Target t = target(s, a[0]);
        if (t == null || exempt(s, t)) return;

        String reason = reason(a, 1);
        int count = addWarn(t.id(), t.name(), reason, s.getName());
        String n = String.valueOf(count);
        if (t.online() != null) {
            t.online().sendMessage(msg("you-warned", "{reason}", reason, "{count}", n));
        }
        broadcast(msg("punish-warn", "{player}", t.name(), "{by}", s.getName(),
                "{reason}", reason, "{count}", n));

        String action = cfg().getString("warn.actions." + count);
        if (action != null && !action.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    action.replace("{player}", t.name()).replace("{count}", n));
        }
    }

    private void warnings(CommandSender s, String[] a) {
        if (a.length < 1) {
            s.sendMessage(msg("usage", "{usage}", "/warnings <Spieler>"));
            return;
        }
        Target t = target(s, a[0]);
        if (t == null) return;
        List<String> list = warns.getOrDefault(t.id(), List.of());
        if (list.isEmpty()) {
            s.sendMessage(msg("warnings-none", "{player}", t.name()));
            return;
        }
        s.sendMessage(msg("warnings-header", "{player}", t.name()));
        for (int i = 0; i < list.size(); i++) {
            s.sendMessage(msg("warnings-entry", "{nr}", String.valueOf(i + 1), "{text}", list.get(i)));
        }
    }

    private void clearWarns(CommandSender s, String[] a) {
        if (a.length < 1) {
            s.sendMessage(msg("usage", "{usage}", "/clearwarns <Spieler>"));
            return;
        }
        Target t = target(s, a[0]);
        if (t == null) return;
        warns.remove(t.id());
        save();
        s.sendMessage(msg("warnings-cleared", "{player}", t.name()));
    }

    private void announce(CommandSender s, String[] a) {
        if (a.length < 1) {
            s.sendMessage(msg("announce-usage"));
            return;
        }
        broadcast(String.join(" ", Arrays.copyOfRange(a, 0, a.length)));
    }

    /** Sendet eine Ankündigung im Format aus config-guard.yml an alle Spieler und in die Konsole. */
    private void broadcast(String text) {
        String line = Ui.color(cfg().getString("announce.format", "{text}").replace("{text}", text));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(line);
        }
        core.getLogger().info(org.bukkit.ChatColor.stripColor(line));
    }

    /** Rotiert durch announce.messages, wenn Intervall gesetzt ist. */
    private void autoAnnounce() {
        List<String> list = cfg().getStringList("announce.messages");
        if (list.isEmpty()) return;
        broadcast(list.get(announceIndex % list.size()));
        announceIndex++;
    }

    private Target target(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return new Target(p.getUniqueId(), p.getName(), p);
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        if (op.hasPlayedBefore() && op.getName() != null) return new Target(op.getUniqueId(), op.getName(), null);
        s.sendMessage(msg("player-not-found", "{player}", name));
        return null;
    }

    /** Geschützte Spieler (falixcore.exempt) kann nur die Konsole bestrafen. */
    private boolean exempt(CommandSender s, Target t) {
        if (t.online() != null && !(s instanceof ConsoleCommandSender) && t.online().hasPermission("falixcore.exempt")) {
            s.sendMessage(msg("player-exempt", "{player}", t.name()));
            return true;
        }
        return false;
    }

    private String reason(String[] a, int from) {
        if (a.length <= from) return cfg().getString("settings.default-reason", "Kein Grund angegeben");
        return String.join(" ", Arrays.copyOfRange(a, from, a.length));
    }

    // ------------------------------------------------------------------
    //  Listener: Sperren durchsetzen
    // ------------------------------------------------------------------

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        Entry ban = get(Type.BAN, e.getUniqueId());
        if (ban == null) return;
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen("ban-screen",
                "{reason}", ban.reason(),
                "{duration}", TimeUtil.format(ban.remaining()),
                "{by}", ban.by()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (get(Type.TIMEOUT, p.getUniqueId()) != null) {
            e.setCancelled(true);
            p.sendMessage(msg("timeout-active", "{duration}", TimeUtil.format(get(Type.TIMEOUT, p.getUniqueId()).remaining())));
            return;
        }
        Entry mute = get(Type.MUTE, p.getUniqueId());
        if (mute != null) {
            e.setCancelled(true);
            p.sendMessage(msg("mute-active", "{duration}", TimeUtil.format(mute.remaining())));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (get(Type.TIMEOUT, p.getUniqueId()) != null) {
            e.setCancelled(true);
            p.sendMessage(msg("timeout-active", "{duration}", TimeUtil.format(get(Type.TIMEOUT, p.getUniqueId()).remaining())));
            return;
        }
        Entry mute = get(Type.MUTE, p.getUniqueId());
        if (mute == null) return;
        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        for (String b : cfg().getStringList("settings.mute-blocked-commands")) {
            if (b.equalsIgnoreCase(label)) {
                e.setCancelled(true);
                p.sendMessage(msg("mute-active", "{duration}", TimeUtil.format(mute.remaining())));
                return;
            }
        }
    }

    private boolean timedOut(Player p) {
        return get(Type.TIMEOUT, p.getUniqueId()) != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        if (!timedOut(e.getPlayer())) return;
        Location frozen = from.clone();
        frozen.setYaw(to.getYaw());
        frozen.setPitch(to.getPitch());
        e.setTo(frozen);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (timedOut(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (timedOut(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (timedOut(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (timedOut(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && timedOut(p)) e.setCancelled(true);
    }

    /** Dauer-Parser: "30s", "10m", "2h", "7d", "1d12h", "perm". */
    static final class TimeUtil {

        private static final java.util.regex.Pattern PART = java.util.regex.Pattern.compile("(\\d+)([smhdwy])");

        private TimeUtil() { }

        /** Millisekunden, -1 = permanent, -2 = ungültig. */
        static long parse(String input) {
            String s = input.toLowerCase(Locale.ROOT);
            if (s.equals("perm") || s.equals("permanent") || s.equals("forever")) return -1;
            java.util.regex.Matcher m = PART.matcher(s);
            long total = 0;
            int end = 0;
            while (m.find()) {
                if (m.start() != end) return -2;
                long n = Long.parseLong(m.group(1));
                total += n * switch (m.group(2)) {
                    case "s" -> 1000L;
                    case "m" -> 60_000L;
                    case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L;
                    case "w" -> 604_800_000L;
                    default -> 31_536_000_000L;
                };
                end = m.end();
            }
            if (end == 0 || end != s.length() || total <= 0) return -2;
            return total;
        }

        static String format(long ms) {
            if (ms < 0) return "Permanent";
            long s = ms / 1000;
            long d = s / 86400; s %= 86400;
            long h = s / 3600; s %= 3600;
            long m = s / 60; s %= 60;
            StringBuilder sb = new StringBuilder();
            if (d > 0) sb.append(d).append("d ");
            if (h > 0) sb.append(h).append("h ");
            if (m > 0) sb.append(m).append("m ");
            if (s > 0 || sb.length() == 0) sb.append(s).append("s");
            return sb.toString().trim();
        }
    }
}
