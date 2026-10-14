package de.falixcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Modul Stats: Sidebar, Bossbar und Tab-Menü mit Rängen. */
public class StatsModule implements Module, Listener {

    private static final int MAX_LINES = 15;

    private record TabGroup(String permission, String prefix) { }

    private final FalixCore core;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Set<UUID> sidebarOff = new HashSet<>();
    private final Set<UUID> bossbarOff = new HashSet<>();
    private BossBar bossBar;

    public StatsModule(FalixCore core) {
        this.core = core;
    }

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public List<String> commands() {
        return List.of("fs");
    }

    @Override
    public void enable() {
        loadBossBar();
        core.getServer().getPluginManager().registerEvents(this, core);
        for (Player p : Bukkit.getOnlinePlayers()) {
            attach(p);
        }
        long period = Math.max(1L, cfg().getLong("settings.update-ticks", 20L));
        Bukkit.getScheduler().runTaskTimer(core, this::tick, 20L, period);
    }

    @Override
    public void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            resetPlayer(p);
        }
        boards.clear();
        if (bossBar != null) bossBar.removeAll();
    }

    @Override
    public void reload() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            resetPlayer(p);
        }
        boards.clear();
        loadBossBar();
        for (Player p : Bukkit.getOnlinePlayers()) {
            attach(p);
        }
    }

    private YamlConfiguration cfg() {
        return core.cfg("stats");
    }

    private String msg(String key, String... pairs) {
        return Ui.msg(core, cfg(), key, pairs);
    }

    // ------------------------------------------------------------------
    //  Befehl /fs
    // ------------------------------------------------------------------

    @Override
    public boolean handle(CommandSender s, String command, String[] a) {
        if (a.length == 0) {
            s.sendMessage(msg("usage", "{usage}", "/fs <sidebar | bossbar | reload>"));
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "sidebar" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(msg("player-only"));
                    return true;
                }
                s.sendMessage(msg(toggleSidebar(p) ? "sidebar-on" : "sidebar-off"));
            }
            case "bossbar" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(msg("player-only"));
                    return true;
                }
                s.sendMessage(msg(toggleBossBar(p) ? "bossbar-on" : "bossbar-off"));
            }
            case "reload" -> {
                if (!s.hasPermission("falixcore.admin")) {
                    s.sendMessage(msg("no-permission"));
                    return true;
                }
                core.reloadAll();
                s.sendMessage(msg("reloaded"));
            }
            default -> s.sendMessage(msg("usage", "{usage}", "/fs <sidebar | bossbar | reload>"));
        }
        return true;
    }

    @Override
    public List<String> complete(CommandSender s, String command, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : List.of("sidebar", "bossbar", "reload")) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    //  Spieler, Sidebar, Bossbar
    // ------------------------------------------------------------------

    private boolean sidebarActive(Player p) {
        return cfg().getBoolean("sidebar.enabled", true) && !sidebarOff.contains(p.getUniqueId());
    }

    private boolean tabEnabled() {
        return cfg().getBoolean("tab.enabled", true);
    }

    private void attach(Player p) {
        rebuild(p);
        if (cfg().getBoolean("bossbar.enabled", true) && !bossbarOff.contains(p.getUniqueId())) {
            bossBar.addPlayer(p);
        }
    }

    private void rebuild(Player p) {
        boolean sidebar = sidebarActive(p);
        if (!sidebar && !tabEnabled()) {
            boards.remove(p.getUniqueId());
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            return;
        }
        Scoreboard board = buildBoard(sidebar);
        boards.put(p.getUniqueId(), board);
        p.setScoreboard(board);
    }

    private void resetPlayer(Player p) {
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        p.setPlayerListHeader("");
        p.setPlayerListFooter("");
        if (bossBar != null) bossBar.removePlayer(p);
    }

    private boolean toggleSidebar(Player p) {
        UUID id = p.getUniqueId();
        if (sidebarOff.remove(id)) {
            rebuild(p);
            return true;
        }
        sidebarOff.add(id);
        rebuild(p);
        return false;
    }

    private boolean toggleBossBar(Player p) {
        UUID id = p.getUniqueId();
        if (bossbarOff.remove(id)) {
            attach(p);
            return true;
        }
        bossbarOff.add(id);
        bossBar.removePlayer(p);
        return false;
    }

    private void loadBossBar() {
        if (bossBar != null) bossBar.removeAll();
        BarColor color = enumOr(BarColor.class, cfg().getString("bossbar.color", "BLUE"), BarColor.BLUE);
        BarStyle style = enumOr(BarStyle.class, cfg().getString("bossbar.style", "SOLID"), BarStyle.SOLID);
        bossBar = Bukkit.createBossBar("", color, style);
        bossBar.setVisible(cfg().getBoolean("bossbar.enabled", true));
    }

    private Scoreboard buildBoard(boolean sidebar) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        if (sidebar) {
            Objective obj = board.registerNewObjective("falixcore", "dummy",
                    Ui.color(cfg().getString("sidebar.title", "&bXCraft")));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            int lines = Math.min(MAX_LINES, cfg().getStringList("sidebar.lines").size());
            for (int i = 0; i < lines; i++) {
                Team team = board.registerNewTeam("fs" + i);
                String entry = entry(i);
                team.addEntry(entry);
                obj.getScore(entry).setScore(lines - i);
            }
        }
        if (tabEnabled()) {
            List<TabGroup> groups = groups();
            for (int i = 0; i < groups.size(); i++) {
                Team team = board.registerNewTeam(teamName(i));
                team.setPrefix(Ui.color(groups.get(i).prefix()));
            }
        }
        return board;
    }

    /** Unsichtbarer, eindeutiger Name pro Sidebar-Zeile (nur Reset-Codes). */
    private static String entry(int i) {
        return org.bukkit.ChatColor.RESET.toString().repeat(i + 1);
    }

    private static String teamName(int i) {
        return "tab" + String.format("%02d", i);
    }

    private List<TabGroup> groups() {
        List<TabGroup> out = new ArrayList<>();
        for (Map<?, ?> m : cfg().getMapList("tab.groups")) {
            Object perm = m.get("permission");
            Object prefix = m.get("prefix");
            out.add(new TabGroup(perm == null ? "" : perm.toString(), prefix == null ? "" : prefix.toString()));
        }
        return out;
    }

    private static int groupIndex(List<TabGroup> groups, Player t) {
        for (int i = 0; i < groups.size(); i++) {
            String perm = groups.get(i).permission();
            if (perm.isBlank() || t.hasPermission(perm)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    //  Aktualisierung
    // ------------------------------------------------------------------

    private void tick() {
        List<String> lines = cfg().getStringList("sidebar.lines");
        int count = Math.min(MAX_LINES, lines.size());
        List<TabGroup> groups = groups();
        boolean tab = tabEnabled();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = boards.get(viewer.getUniqueId());

            if (board != null && sidebarActive(viewer)) {
                for (int i = 0; i < count; i++) {
                    Team team = board.getTeam("fs" + i);
                    if (team != null) {
                        team.setPrefix(limit(Ui.color(replace(lines.get(i), viewer))));
                    }
                }
            }

            if (tab) {
                if (board != null) updateTabTeams(board, groups);
                viewer.setPlayerListHeader(Ui.color(replace(joinLines("tab.header"), viewer)));
                viewer.setPlayerListFooter(Ui.color(replace(joinLines("tab.footer"), viewer)));
            }
        }

        if (cfg().getBoolean("bossbar.enabled", true)) {
            bossBar.setTitle(Ui.color(replace(cfg().getString("bossbar.text", ""), null)));
            bossBar.setProgress(progress());
        }
    }

    private void updateTabTeams(Scoreboard board, List<TabGroup> groups) {
        for (Player target : Bukkit.getOnlinePlayers()) {
            String name = target.getName();
            int want = groupIndex(groups, target);
            for (int i = 0; i < groups.size(); i++) {
                Team team = board.getTeam(teamName(i));
                if (team == null) continue;
                if (i == want) {
                    if (!team.hasEntry(name)) team.addEntry(name);
                } else if (team.hasEntry(name)) {
                    team.removeEntry(name);
                }
            }
        }
    }

    private String joinLines(String path) {
        return String.join("\n", cfg().getStringList(path));
    }

    /** Schneidet eine Zeile auf max-length sichtbare Zeichen ab. Farbcodes zählen nicht. */
    private String limit(String s) {
        int max = cfg().getInt("sidebar.max-length", 28);
        StringBuilder out = new StringBuilder();
        int visible = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) {
                out.append(c).append(s.charAt(i + 1));
                i++;
                continue;
            }
            if (visible >= max) break;
            out.append(c);
            visible++;
        }
        return out.toString();
    }

    private double progress() {
        String mode = cfg().getString("bossbar.progress-mode", "fixed").toLowerCase(Locale.ROOT);
        double value;
        switch (mode) {
            case "online" -> value = Bukkit.getMaxPlayers() == 0 ? 0
                    : (double) Bukkit.getOnlinePlayers().size() / Bukkit.getMaxPlayers();
            case "time" -> value = Bukkit.getWorlds().isEmpty() ? 0
                    : Math.floorMod(Bukkit.getWorlds().get(0).getTime(), 24000L) / 24000.0;
            default -> value = cfg().getDouble("bossbar.fixed-progress", 1.0);
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String replace(String text, Player p) {
        World world = p != null ? p.getWorld()
                : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
        Location loc = p != null ? p.getLocation() : null;

        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long max = rt.maxMemory() / 1048576L;

        String ultimate = "-";
        if (p != null && core.ultimate() != null) {
            ultimate = core.ultimate().daysLeftText(p.getUniqueId());
        }

        return text
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("{player}", p != null ? p.getName() : "")
                .replace("{world-players}", String.valueOf(world != null ? world.getPlayers().size() : 0))
                .replace("{world}", world != null ? world.getName() : "-")
                .replace("{x}", loc != null ? String.valueOf(loc.getBlockX()) : "-")
                .replace("{y}", loc != null ? String.valueOf(loc.getBlockY()) : "-")
                .replace("{z}", loc != null ? String.valueOf(loc.getBlockZ()) : "-")
                .replace("{time}", world != null ? ingameTime(world) : "-")
                .replace("{clock}", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                .replace("{ram}", used + "/" + max + " MB")
                .replace("{ultimate}", ultimate);
    }

    private static String ingameTime(World w) {
        long t = Math.floorMod(w.getTime(), 24000L);
        int hours = (int) ((t / 1000 + 6) % 24);
        int minutes = (int) ((t % 1000) * 60 / 1000);
        return String.format("%02d:%02d", hours, minutes);
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String value, E def) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return def;
        }
    }

    // ------------------------------------------------------------------
    //  Listener
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        attach(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        if (bossBar != null) bossBar.removePlayer(e.getPlayer());
    }
}
