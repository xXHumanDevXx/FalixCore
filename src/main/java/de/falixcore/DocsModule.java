package de.falixcore;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** Modul Docs: /help, /about, /links, /info und /docs (eigene Seiten erstellen). */
public class DocsModule implements Module {

    private static final String FILE = "data-docs.yml";

    private final FalixCore core;
    private YamlConfiguration custom = new YamlConfiguration();

    public DocsModule(FalixCore core) {
        this.core = core;
    }

    @Override
    public String name() {
        return "docs";
    }

    @Override
    public List<String> commands() {
        return List.of("help", "about", "links", "info", "docs");
    }

    @Override
    public void enable() {
        File file = core.dataFile(FILE);
        if (file.exists()) custom = YamlConfiguration.loadConfiguration(file);
    }

    @Override
    public void disable() {
        save();
    }

    @Override
    public void reload() {
        // Config wird bei jedem Zugriff frisch gelesen
    }

    private YamlConfiguration cfg() {
        return core.cfg("docs");
    }

    private String msg(String key, String... pairs) {
        return Ui.msg(core, cfg(), key, pairs);
    }

    @Override
    public boolean handle(CommandSender s, String command, String[] a) {
        switch (command) {
            case "help", "about" -> showPage(s, command, a.length > 0 ? a[0] : null);
            case "links" -> showLinks(s);
            case "info" -> {
                if (a.length == 0) listPages(s);
                else showPage(s, a[0], null);
            }
            case "docs" -> docs(s, a);
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> complete(CommandSender s, String command, String[] a) {
        List<String> out = new ArrayList<>();
        if (command.equals("info") && a.length == 1) {
            out.addAll(allPages());
        } else if (command.equals("docs") && a.length == 1) {
            out.addAll(List.of("create", "addline", "delete", "list", "reload"));
        }
        String start = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.startsWith(start));
        return out;
    }

    // ------------------------------------------------------------------
    //  Seiten anzeigen
    // ------------------------------------------------------------------

    private void showPage(CommandSender s, String name, String ignored) {
        String key = name.toLowerCase(Locale.ROOT);
        String title;
        List<String> lines;
        if (custom.contains("pages." + key)) {
            title = custom.getString("pages." + key + ".title", key);
            lines = custom.getStringList("pages." + key + ".lines");
        } else if (cfg().contains("pages." + key)) {
            title = cfg().getString("pages." + key + ".title", key);
            lines = cfg().getStringList("pages." + key + ".lines");
        } else {
            s.sendMessage(msg("docs-not-found", "{page}", name));
            return;
        }
        Player p = s instanceof Player pl ? pl : null;
        s.sendMessage(Ui.header(core, title));
        for (String line : lines) {
            s.sendMessage(Ui.color(replace(line, p)));
        }
    }

    private void listPages(CommandSender s) {
        s.sendMessage(msg("docs-list-header"));
        for (String page : allPages()) {
            s.sendMessage(msg("docs-list-entry", "{page}", page));
        }
    }

    private TreeSet<String> allPages() {
        TreeSet<String> names = new TreeSet<>();
        addKeys(names, cfg().getConfigurationSection("pages"));
        addKeys(names, custom.getConfigurationSection("pages"));
        return names;
    }

    private static void addKeys(TreeSet<String> names, ConfigurationSection sec) {
        if (sec != null) names.addAll(sec.getKeys(false));
    }

    /** Links: im Chat anklickbar, mit Hover-Adresse. Auf der Konsole als Text. */
    private void showLinks(CommandSender s) {
        Player p = s instanceof Player pl ? pl : null;
        s.sendMessage(Ui.header(core, cfg().getString("links.title", "Links")));

        List<Map<?, ?>> entries = cfg().getMapList("links.entries");
        for (Map<?, ?> e : entries) {
            String name = text(e.get("name"));
            String url = text(e.get("url"));
            String description = text(e.get("description"));

            if (p == null) {
                s.sendMessage(Ui.color("&b" + name + " &8- &7" + url));
                continue;
            }
            TextComponent line = new TextComponent(Ui.color("&b» " + name));
            line.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(Ui.color("&7Klicken zum Öffnen:\n&f" + url)).create()));
            line.addExtra(new TextComponent(Ui.color("  &8- &7" + description)));
            p.spigot().sendMessage(line);
        }
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    private String replace(String text, Player p) {
        return text
                .replace("{player}", p != null ? p.getName() : "Spieler")
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()));
    }

    // ------------------------------------------------------------------
    //  /docs create | addline | delete | list | reload
    // ------------------------------------------------------------------

    private void docs(CommandSender s, String[] a) {
        if (a.length == 0) {
            s.sendMessage(msg("docs-usage"));
            return;
        }
        if (a[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission("falixcore.docs.admin")) {
                s.sendMessage(msg("no-permission"));
                return;
            }
            core.reloadAll();
            s.sendMessage(msg("reloaded"));
            return;
        }
        if (!s.hasPermission("falixcore.docs.admin")) {
            s.sendMessage(msg("no-permission"));
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (a.length < 3) {
                    s.sendMessage(msg("docs-usage"));
                    return;
                }
                String key = a[1].toLowerCase(Locale.ROOT);
                if (custom.contains("pages." + key) || cfg().contains("pages." + key)) {
                    s.sendMessage(msg("docs-exists", "{page}", key));
                    return;
                }
                custom.set("pages." + key + ".title", String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                custom.set("pages." + key + ".lines", new ArrayList<String>());
                save();
                s.sendMessage(msg("docs-created", "{page}", key));
            }
            case "addline" -> {
                if (a.length < 3) {
                    s.sendMessage(msg("docs-usage"));
                    return;
                }
                String key = a[1].toLowerCase(Locale.ROOT);
                if (!custom.contains("pages." + key)) {
                    s.sendMessage(msg("docs-not-editable", "{page}", key));
                    return;
                }
                List<String> lines = new ArrayList<>(custom.getStringList("pages." + key + ".lines"));
                lines.add(String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                custom.set("pages." + key + ".lines", lines);
                save();
                s.sendMessage(msg("docs-line-added", "{page}", key));
            }
            case "delete" -> {
                if (a.length < 2) {
                    s.sendMessage(msg("docs-usage"));
                    return;
                }
                String key = a[1].toLowerCase(Locale.ROOT);
                if (!custom.contains("pages." + key)) {
                    s.sendMessage(msg("docs-not-editable", "{page}", key));
                    return;
                }
                custom.set("pages." + key, null);
                save();
                s.sendMessage(msg("docs-deleted", "{page}", key));
            }
            case "list" -> listPages(s);
            default -> s.sendMessage(msg("docs-usage"));
        }
    }

    private synchronized void save() {
        try {
            custom.save(core.dataFile(FILE));
        } catch (IOException ex) {
            core.getLogger().severe(FILE + " konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }
}
