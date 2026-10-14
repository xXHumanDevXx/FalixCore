package de.falixcore;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Modul Control: blendet Befehle je Gruppe oder Spieler aus und sperrt sie. Hat keinen eigenen Befehl. */
public class ControlModule implements Module, Listener {

    /** Erlaubte Befehle eines Spielers. all = alles erlaubt. */
    public record Access(boolean all, Set<String> commands, String source) {

        public boolean allows(String label) {
            if (all || commands.contains("*")) return true;
            String l = label.toLowerCase(Locale.ROOT);
            if (l.startsWith("/")) l = l.substring(1);
            String base = l.contains(":") ? l.substring(l.indexOf(':') + 1) : l;
            return commands.contains(l) || commands.contains(base);
        }
    }

    private final FalixCore core;

    public ControlModule(FalixCore core) {
        this.core = core;
    }

    @Override
    public String name() {
        return "control";
    }

    @Override
    public List<String> commands() {
        return Collections.emptyList();
    }

    @Override
    public void enable() {
        core.getServer().getPluginManager().registerEvents(this, core);
    }

    @Override
    public void disable() {
        // Keine Daten zu speichern
    }

    @Override
    public void reload() {
        // Config wird bei jedem Zugriff frisch gelesen
    }

    @Override
    public boolean handle(CommandSender s, String command, String[] args) {
        return false;
    }

    @Override
    public List<String> complete(CommandSender s, String command, String[] args) {
        return Collections.emptyList();
    }

    private YamlConfiguration cfg() {
        return core.cfg("control");
    }

    private Set<String> normalize(List<String> list) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : list) {
            if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Reihenfolge: Bypass-Recht, Spieler-Eintrag, Gruppe, Standardgruppe. */
    public Access access(Player p) {
        boolean opsBypass = cfg().getBoolean("settings.ops-bypass", false);
        if (p.hasPermission("falixcore.bypass") && (opsBypass || !p.isOp())) {
            return new Access(true, Collections.emptySet(), "Bypass");
        }
        ConfigurationSection players = cfg().getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                if (key.equalsIgnoreCase(p.getName())) {
                    return new Access(false, normalize(players.getStringList(key)), "Spieler-Eintrag");
                }
            }
        }
        ConfigurationSection groups = cfg().getConfigurationSection("groups");
        if (groups != null) {
            for (String key : groups.getKeys(false)) {
                if (p.hasPermission("falixcore.group." + key.toLowerCase(Locale.ROOT))) {
                    return new Access(false, normalize(groups.getStringList(key + ".commands")), "Gruppe " + key);
                }
            }
        }
        String def = cfg().getString("settings.default-group", "spieler");
        List<String> defList = groups == null ? new ArrayList<>() : groups.getStringList(def + ".commands");
        return new Access(false, normalize(defList), "Standardgruppe " + def);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandSend(PlayerCommandSendEvent e) {
        if (!cfg().getBoolean("settings.hide-commands", true)) return;
        Access a = access(e.getPlayer());
        if (a.all() || a.commands().contains("*")) return;
        e.getCommands().removeIf(c -> !a.allows(c));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim();
        if (msg.length() < 2) return;
        String label = msg.substring(1).split(" ")[0];
        if (access(e.getPlayer()).allows(label)) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(Ui.msg(core, cfg(), "control-blocked", "{command}", label));
    }
}
