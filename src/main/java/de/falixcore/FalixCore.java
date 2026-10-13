package de.falixcore;

import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hauptklasse: lädt Module und deren eigene config-Dateien. */
public class FalixCore extends JavaPlugin {

    private static final List<String> CONFIG_NAMES = List.of("teleport", "docs");

    private final Map<String, YamlConfiguration> configs = new HashMap<>();
    private final List<Module> modules = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String n : CONFIG_NAMES) {
            configs.put(n, loadConfig(n));
        }

        if (getConfig().getBoolean("modules.teleport", true)) modules.add(new TeleportModule(this));
        if (getConfig().getBoolean("modules.docs", true)) modules.add(new DocsModule(this));

        Dispatcher dispatcher = new Dispatcher(modules);
        for (String cmd : dispatcher.commands()) {
            PluginCommand pc = getCommand(cmd);
            if (pc != null) {
                pc.setExecutor(dispatcher);
                pc.setTabCompleter(dispatcher);
            }
        }
        for (Module m : modules) {
            m.enable();
        }
        getLogger().info("FalixCore aktiviert, Module geladen: " + modules.size());
    }

    @Override
    public void onDisable() {
        for (Module m : modules) {
            m.disable();
        }
    }

    /** Config eines Moduls, z. B. cfg("teleport") liest config-teleport.yml. */
    public YamlConfiguration cfg(String name) {
        return configs.get(name);
    }

    public void reloadAll() {
        reloadConfig();
        for (String n : CONFIG_NAMES) {
            configs.put(n, loadConfig(n));
        }
        for (Module m : modules) {
            m.reload();
        }
    }

    private YamlConfiguration loadConfig(String name) {
        String file = "config-" + name + ".yml";
        if (!new File(getDataFolder(), file).exists()) {
            saveResource(file, false);
        }
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), file));
    }

    /** Datei im Plugin-Ordner, z. B. für Spieldaten. */
    public File dataFile(String name) {
        getDataFolder().mkdirs();
        return new File(getDataFolder(), name);
    }

    public String prefix() {
        return getConfig().getString("prefix", "");
    }

    public String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
