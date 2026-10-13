package de.falixcore;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Modul Ultimate: zeitlich begrenzte Abos, vergibt die Perk-Rechte aus config-ultimate.yml. */
public class UltimateModule implements Module, Listener {

    public record Subscription(String player, long until) {
        public boolean active() {
            return until > System.currentTimeMillis();
        }
    }

    private static final long DAY = 86_400_000L;
    private static final String FILE = "data-ultimate.yml";
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private record Target(UUID id, String name) { }

    private final FalixCore core;
    private final File file;
    private final Map<UUID, Subscription> subs = new HashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public UltimateModule(FalixCore core) {
        this.core = core;
        this.file = core.dataFile(FILE);
    }

    @Override
    public String name() {
        return "ultimate";
    }

    @Override
    public List<String> commands() {
        return List.of("ultimate");
    }

    @Override
    public void enable() {
        load();
        core.getServer().getPluginManager().registerEvents(this, core);
        for (Player p : Bukkit.getOnlinePlayers()) {
            apply(p);
        }
        // Jede Minute abgelaufene Abos entfernen (1200 Ticks)
        Bukkit.getScheduler().runTaskTimer(core, this::checkExpired, 1200L, 1200L);
    }

    @Override
    public void disable() {
        save();
    }

    @Override
    public void reload() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            apply(p);
        }
    }

    private YamlConfiguration cfg() {
        return core.cfg("ultimate");
    }

    private String msg(String key, String... pairs) {
        List<String> args = new ArrayList<>(List.of("{name}", perkName(), "{price}", cfg().getString("perks.price", "")));
        args.addAll(List.of(pairs));
        return Ui.msg(core, cfg(), key, args.toArray(new String[0]));
    }

    private String perkName() {
        return cfg().getString("perks.name", "Ultimate");
    }

    // ------------------------------------------------------------------
    //  Abos
    // ------------------------------------------------------------------

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                subs.put(UUID.fromString(key),
                        new Subscription(sec.getString(key + ".name", "?"), sec.getLong(key + ".until", 0L)));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Subscription> en : subs.entrySet()) {
            String base = "players." + en.getKey() + ".";
            y.set(base + "name", en.getValue().player());
            y.set(base + "until", en.getValue().until());
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            core.getLogger().severe(FILE + " konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    public boolean isActive(UUID id) {
        Subscription s = subs.get(id);
        return s != null && s.active();
    }

    /** Restlaufzeit in Tagen, z. B. "12 Tage", "1 Tag" oder "-" ohne aktives Abo. */
    public String daysLeftText(UUID id) {
        Subscription s = subs.get(id);
        if (s == null || !s.active()) return "-";
        long days = (s.until() - System.currentTimeMillis() + DAY - 1) / DAY;
        return days + (days == 1 ? " Tag" : " Tage");
    }

    public void grant(UUID id, String name, int days) {
        Subscription old = subs.get(id);
        long now = System.currentTimeMillis();
        long base = (old != null && old.until() > now) ? old.until() : now;
        subs.put(id, new Subscription(name, base + days * DAY));
        save();
        Player p = Bukkit.getPlayer(id);
        if (p != null) apply(p);
    }

    public boolean revoke(UUID id) {
        Subscription removed = subs.remove(id);
        save();
        Player p = Bukkit.getPlayer(id);
        if (p != null) clear(p);
        return removed != null;
    }

    private void apply(Player p) {
        clear(p);
        if (!isActive(p.getUniqueId())) return;
        PermissionAttachment att = p.addAttachment(core);
        for (String node : cfg().getStringList("perks.permissions")) {
            att.setPermission(node, true);
        }
        attachments.put(p.getUniqueId(), att);
    }

    private void clear(Player p) {
        PermissionAttachment att = attachments.remove(p.getUniqueId());
        if (att != null) p.removeAttachment(att);
    }

    private void checkExpired() {
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Subscription> en : subs.entrySet()) {
            if (!en.getValue().active()) expired.add(en.getKey());
        }
        if (expired.isEmpty()) return;
        for (UUID id : expired) {
            subs.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                clear(p);
                p.sendMessage(msg("ultimate-expired"));
            }
        }
        save();
    }

    private String expiryText(Subscription s) {
        return FMT.format(Instant.ofEpochMilli(s.until()));
    }

    // ------------------------------------------------------------------
    //  Befehl /ultimate
    // ------------------------------------------------------------------

    @Override
    public boolean handle(CommandSender s, String command, String[] a) {
        if (a.length == 0) {
            status(s);
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "info" -> info(s, a);
            case "grant" -> grant(s, a);
            case "remove" -> remove(s, a);
            case "list" -> list(s);
            default -> s.sendMessage(msg("ultimate-usage"));
        }
        return true;
    }

    @Override
    public List<String> complete(CommandSender s, String command, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : List.of("info", "grant", "remove", "list")) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        }
        return out;
    }

    private void status(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(msg("ultimate-usage"));
            return;
        }
        Subscription sub = subs.get(p.getUniqueId());
        if (sub != null && sub.active()) {
            p.sendMessage(msg("ultimate-status-active",
                    "{until}", expiryText(sub), "{days}", daysLeftText(p.getUniqueId())));
        } else {
            p.sendMessage(msg("ultimate-status-inactive"));
        }
    }

    private void info(CommandSender s, String[] a) {
        if (!admin(s)) return;
        if (a.length < 2) {
            s.sendMessage(msg("ultimate-usage"));
            return;
        }
        Target t = resolve(s, a[1]);
        if (t == null) return;
        Subscription sub = subs.get(t.id());
        if (sub != null && sub.active()) {
            s.sendMessage(msg("ultimate-info-active", "{player}", t.name(), "{until}", expiryText(sub)));
        } else {
            s.sendMessage(msg("ultimate-info-inactive", "{player}", t.name()));
        }
    }

    private void grant(CommandSender s, String[] a) {
        if (!admin(s)) return;
        if (a.length < 3) {
            s.sendMessage(msg("ultimate-usage"));
            return;
        }
        int days;
        try {
            days = Integer.parseInt(a[2]);
        } catch (NumberFormatException ex) {
            s.sendMessage(msg("ultimate-invalid-days"));
            return;
        }
        if (days <= 0) {
            s.sendMessage(msg("ultimate-invalid-days"));
            return;
        }
        Target t = resolve(s, a[1]);
        if (t == null) return;

        grant(t.id(), t.name(), days);
        Subscription sub = subs.get(t.id());
        String until = expiryText(sub);
        s.sendMessage(msg("ultimate-granted", "{player}", t.name(),
                "{days}", String.valueOf(days), "{until}", until));
        Player online = Bukkit.getPlayer(t.id());
        if (online != null) {
            online.sendMessage(msg("ultimate-granted-you",
                    "{days}", String.valueOf(days), "{until}", until));
        }
    }

    private void remove(CommandSender s, String[] a) {
        if (!admin(s)) return;
        if (a.length < 2) {
            s.sendMessage(msg("ultimate-usage"));
            return;
        }
        Target t = resolve(s, a[1]);
        if (t == null) return;
        if (revoke(t.id())) {
            s.sendMessage(msg("ultimate-revoked", "{player}", t.name()));
        } else {
            s.sendMessage(msg("ultimate-not-active", "{player}", t.name()));
        }
    }

    private void list(CommandSender s) {
        if (!admin(s)) return;
        List<UUID> active = new ArrayList<>();
        for (Map.Entry<UUID, Subscription> en : subs.entrySet()) {
            if (en.getValue().active()) active.add(en.getKey());
        }
        if (active.isEmpty()) {
            s.sendMessage(msg("ultimate-list-none"));
            return;
        }
        s.sendMessage(msg("ultimate-list-header", "{count}", String.valueOf(active.size())));
        for (UUID id : active) {
            Subscription sub = subs.get(id);
            s.sendMessage(msg("ultimate-list-entry", "{player}", sub.player(), "{until}", expiryText(sub)));
        }
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("falixcore.ultimate.admin")) return true;
        s.sendMessage(msg("ultimate-no-permission"));
        return false;
    }

    private Target resolve(CommandSender s, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new Target(online.getUniqueId(), online.getName());
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        if (!op.hasPlayedBefore()) {
            s.sendMessage(msg("ultimate-player-not-found", "{player}", name));
            return null;
        }
        return new Target(op.getUniqueId(), name);
    }

    // ------------------------------------------------------------------
    //  Listener
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        apply(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        clear(e.getPlayer());
    }
}
