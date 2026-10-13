package de.falixcore;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

/** Einheitliches Design für alle Module: Nachrichten, Überschriften und Menütitel. */
public final class Ui {

    private Ui() { }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Nachricht aus messages.<key> im Modul, Platzhalter als Paare: "{player}", name, ... */
    public static String msg(FalixCore core, YamlConfiguration cfg, String key, String... pairs) {
        String raw = cfg.getString("messages." + key, "&cFehlende Nachricht: " + key);
        raw = raw.replace("{prefix}", core.prefix());
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            raw = raw.replace(pairs[i], pairs[i + 1]);
        }
        return color(raw);
    }

    /** Überschrift mit Trennlinien, überall gleich. */
    public static String header(FalixCore core, String title) {
        String line = core.getConfig().getString("ui.line", "&8&m                    ");
        return color(line + "&r &b&l" + title + " " + line);
    }
}
