package de.falixcore;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hauptklasse: lädt Module und deren eigene config-Dateien. Reload startet alles sauber neu. */
public class FalixCore extends JavaPlugin {

    private static final List<String> CONFIG_NAMES =
            List.of("teleport", "docs", "stats", "ultimate", "protect", "control", "guard", "anticheat", "menu");

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
        startModules();
        getLogger().info("FalixCore aktiviert, Module geladen: " + modules.size());
    }

    @Override
    public void onDisable() {
        stopModules();
    }

    /** Alles neu laden: Config, Module, Listener, Timer und Befehle. */
    public void reloadAll() {
        reloadConfig();
        stopModules();
        startModules();
        // Befehlsliste aller Online-Spieler neu senden, damit Sperren sofort greifen
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            p.updateCommands();
        }
    }

    private void loadConfigs() {
        configs.clear();
        for (String n : CONFIG_NAMES) {
            configs.put(n, loadConfig(n));
        }
    }

    private void startModules() {
        loadConfigs();
        modules.clear();
        teleport = null;
        docs = null;
        stats = null;
        ultimate = null;
        protect = null;

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

        if (getConfig().getBoolean("modules.menu", true)) {
            modules.add(new MenuModule(this));
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
    }

    private void stopModules() {
        HandlerList.unregisterAll(this);
        Bukkit.getScheduler().cancelTasks(this);
        for (Module m : modules) {
            m.disable();
        }
        modules.clear();
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
