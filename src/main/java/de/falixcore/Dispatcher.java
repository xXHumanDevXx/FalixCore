package de.falixcore;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Leitet jeden Befehl an das Modul weiter, das ihn bearbeitet. */
public class Dispatcher implements CommandExecutor, TabCompleter {

    private final Map<String, Module> byCommand = new HashMap<>();

    public Dispatcher(List<Module> modules) {
        for (Module m : modules) {
            for (String c : m.commands()) byCommand.put(c, m);
        }
    }

    public Set<String> commands() {
        return byCommand.keySet();
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String name = c.getName().toLowerCase(Locale.ROOT);
        Module m = byCommand.get(name);
        return m != null && m.handle(s, name, a);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        String name = c.getName().toLowerCase(Locale.ROOT);
        Module m = byCommand.get(name);
        return m == null ? List.of() : m.complete(s, name, a);
    }
}
