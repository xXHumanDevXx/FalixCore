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

    private static final List<String> CONFIG_NAMES = List.of("teleport", "docs", "stats", "ultimate", "protect", "control", "guard", "anticheat");

    private final Map<String, YamlConfiguration> configs = new HashMap<>();
    private final List<Module> modules = new ArrayList<>();

    private TeleportModule teleport;
    private DocsModule docs;
    private StatsModule stats;
    private UltimateModule ultimate;
    private ProtectModule protect;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String n : CONFIG_NAMES) {
            configs.put(n, loadConfig(n));
        }

        if (getConfig().getBoolean("modules.ultimate", true)) {
            ultimate = new UltimateModule(this);
            modules.add(ultimate);
        }
        if (getConfig().getBoolean("modules.teleport", true)) {
            teleport = new TeleportModule(this);
            modules.add(teleport);
        }
        if (getConfig().getBoolean("modules.docs", true)) {
            docs = new DocsModule(this);
            modules.add(docs);
        }
        if (getConfig().getBoolean("modules.stats", true)) {
            stats = new StatsModule(this);
            modules.add(stats);
        }
        if (getConfig().getBoolean("modules.protect", true)) {
            protect = new ProtectModule(this);
            modules.add(protect);
        }

        if (getConfig().getBoolean("modules.control", true)) {
            modules.add(new ControlModule(this));
        }
        if (getConfig().getBoolean("modules.guard", true)) {
            modules.add(new GuardModule(this));
        }

        if (getConfig().getBoolean("modules.anticheat", true)) {
            modules.add(new AntiCheatModule(this));
        }

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

    public TeleportModule teleport() { return teleport; }
    public DocsModule docs() { return docs; }
    public StatsModule stats() { return stats; }
    public UltimateModule ultimate() { return ultimate; }
    public ProtectModule protect() { return protect; }

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
