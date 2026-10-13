package de.falixcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Warps und Homes. Format: wie bisher in data-teleport.yml. */
public class TeleportStore {

    private static final String FILE = "data-teleport.yml";

    private final FalixCore core;
    private final Map<String, Location> warps = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Location>> homes = new HashMap<>();

    public TeleportStore(FalixCore core) {
        this.core = core;
    }

    public void load() {
        File file = core.dataFile(FILE);
        if (!file.exists()) core.saveResource(FILE, false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);

        for (String line : y.getStringList("warps")) {
            String[] p = line.split(";");
            if (p.length < 7) continue;
            Location l = dec(p, 1);
            if (l != null) warps.put(key(p[0]), l);
        }
        for (String line : y.getStringList("homes")) {
            String[] p = line.split(";");
            if (p.length < 8) continue;
            try {
                UUID id = UUID.fromString(p[0]);
                Location l = dec(p, 2);
                if (l != null) homes.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(key(p[1]), l);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public synchronized void save() {
        List<String> w = new ArrayList<>();
        warps.forEach((n, l) -> w.add(n + ";" + enc(l)));

        List<String> h = new ArrayList<>();
        homes.forEach((id, m) -> m.forEach((n, l) -> h.add(id + ";" + n + ";" + enc(l))));

        YamlConfiguration y = new YamlConfiguration();
        y.set("warps", w);
        y.set("homes", h);
        try {
            y.save(core.dataFile(FILE));
        } catch (IOException ex) {
            core.getLogger().severe(FILE + " konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    public void setWarp(String name, Location l) {
        warps.put(key(name), l.clone());
        save();
    }

    public boolean delWarp(String name) {
        boolean removed = warps.remove(key(name)) != null;
        if (removed) save();
        return removed;
    }

    public Location getWarp(String name) {
        return warps.get(key(name));
    }

    public Map<String, Location> warps() {
        return Collections.unmodifiableMap(warps);
    }

    public void setHome(UUID id, String name, Location l) {
        homes.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(key(name), l.clone());
        save();
    }

    public boolean delHome(UUID id, String name) {
        Map<String, Location> m = homes.get(id);
        if (m == null) return false;
        boolean removed = m.remove(key(name)) != null;
        if (m.isEmpty()) homes.remove(id);
        if (removed) save();
        return removed;
    }

    public Location getHome(UUID id, String name) {
        Map<String, Location> m = homes.get(id);
        return m == null ? null : m.get(key(name));
    }

    public Map<String, Location> homes(UUID id) {
        Map<String, Location> m = homes.get(id);
        return m == null ? Map.of() : Collections.unmodifiableMap(m);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String enc(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ()
                + ";" + l.getYaw() + ";" + l.getPitch();
    }

    private static Location dec(String[] p, int off) {
        World w = Bukkit.getWorld(p[off]);
        if (w == null) return null;
        try {
            return new Location(w,
                    Double.parseDouble(p[off + 1]), Double.parseDouble(p[off + 2]), Double.parseDouble(p[off + 3]),
                    Float.parseFloat(p[off + 4]), Float.parseFloat(p[off + 5]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
