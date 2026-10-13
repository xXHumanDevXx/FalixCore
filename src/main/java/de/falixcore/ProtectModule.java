package de.falixcore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Modul Protect: Schutzblöcke, die einen Bereich vor Abbau und Explosionen schützen. */
public class ProtectModule implements Module, Listener {

    public record Protector(String world, int x, int y, int z, UUID owner, String ownerName) {

        boolean sameBlock(Block b) {
            return b.getX() == x && b.getY() == y && b.getZ() == z && b.getWorld().getName().equals(world);
        }
    }

    private static final String FILE = "data-protect.yml";

    private final FalixCore core;
    private final NamespacedKey key;
    private final File file;
    private final List<Protector> list = new CopyOnWriteArrayList<>();

    public ProtectModule(FalixCore core) {
        this.core = core;
        this.key = new NamespacedKey(core, "protector");
        this.file = core.dataFile(FILE);
    }

    @Override
    public String name() {
        return "protect";
    }

    @Override
    public List<String> commands() {
        return List.of("protection");
    }

    @Override
    public void enable() {
        load();
        core.getServer().getPluginManager().registerEvents(this, core);
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
        return core.cfg("protect");
    }

    private String msg(String key, String... pairs) {
        return Ui.msg(core, cfg(), key, pairs);
    }

    // ------------------------------------------------------------------
    //  Daten
    // ------------------------------------------------------------------

    private void load() {
        if (!file.exists()) core.saveResource(FILE, false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String line : y.getStringList("protectors")) {
            String[] parts = line.split(";");
            if (parts.length < 6) continue;
            try {
                list.add(new Protector(parts[0],
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                        UUID.fromString(parts[4]), parts[5]));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private synchronized void save() {
        List<String> out = new ArrayList<>();
        for (Protector p : list) {
            out.add(p.world() + ";" + p.x() + ";" + p.y() + ";" + p.z() + ";" + p.owner() + ";" + p.ownerName());
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("protectors", out);
        try {
            y.save(file);
        } catch (IOException ex) {
            core.getLogger().severe(FILE + " konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    private Protector at(Block b) {
        for (Protector p : list) {
            if (p.sameBlock(b)) return p;
        }
        return null;
    }

    /** Schutzblock, der diesen Block für den Spieler sperrt. p == null = Explosion (ohne Bypass). */
    private Protector blocking(Block b, Player p) {
        if (p != null && p.hasPermission("falixcore.protect.bypass")) return null;
        int r = cfg().getInt("settings.radius-chunks", 1);
        boolean ownerBypass = cfg().getBoolean("settings.owner-bypass", false);
        int cx = b.getX() >> 4;
        int cz = b.getZ() >> 4;
        String world = b.getWorld().getName();
        for (Protector pr : list) {
            if (!pr.world().equals(world)) continue;
            if (Math.abs((pr.x() >> 4) - cx) > r) continue;
            if (Math.abs((pr.z() >> 4) - cz) > r) continue;
            if (p != null && ownerBypass && p.getUniqueId().equals(pr.owner())) continue;
            return pr;
        }
        return null;
    }

    private ItemStack createItem() {
        Material mat = Material.matchMaterial(cfg().getString("settings.item-material", "BEACON"));
        if (mat == null) mat = Material.BEACON;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Ui.color(cfg().getString("item.name", "&6Schutzblock")));
        List<String> lore = new ArrayList<>();
        for (String line : cfg().getStringList("item.lore")) lore.add(Ui.color(line));
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "protector");
        item.setItemMeta(meta);
        return item;
    }

    private boolean isProtectorItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------
    //  Befehl /protection
    // ------------------------------------------------------------------

    @Override
    public boolean handle(CommandSender s, String command, String[] a) {
        if (a.length == 0) {
            giveItem(s);
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (!s.hasPermission("falixcore.protect.admin")) s.sendMessage(msg("protect-no-permission"));
                else list(s);
            }
            case "reload" -> {
                if (!s.hasPermission("falixcore.protect.admin")) s.sendMessage(msg("protect-no-permission"));
                else {
                    core.reloadAll();
                    s.sendMessage(msg("protect-reloaded"));
                }
            }
            default -> s.sendMessage(msg("protect-usage"));
        }
        return true;
    }

    @Override
    public List<String> complete(CommandSender s, String command, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : List.of("list", "reload")) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        }
        return out;
    }

    private void giveItem(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(msg("protect-usage"));
            return;
        }
        if (!p.hasPermission("falixcore.protect.use")) {
            p.sendMessage(msg("protect-no-permission"));
            return;
        }
        Map<Integer, ItemStack> left = p.getInventory().addItem(createItem());
        if (left.isEmpty()) {
            p.sendMessage(msg("protect-received"));
        } else {
            left.values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
            p.sendMessage(msg("protect-inventory-full"));
        }
    }

    private void list(CommandSender s) {
        if (list.isEmpty()) {
            s.sendMessage(msg("protect-list-none"));
            return;
        }
        s.sendMessage(msg("protect-list-header", "{count}", String.valueOf(list.size())));
        for (Protector p : list) {
            s.sendMessage(msg("protect-list-entry", "{world}", p.world(),
                    "{x}", String.valueOf(p.x()), "{y}", String.valueOf(p.y()),
                    "{z}", String.valueOf(p.z()), "{owner}", p.ownerName()));
        }
    }

    // ------------------------------------------------------------------
    //  Listener
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!isProtectorItem(e.getItemInHand())) return;
        Protector p = new Protector(e.getBlockPlaced().getWorld().getName(),
                e.getBlockPlaced().getX(), e.getBlockPlaced().getY(), e.getBlockPlaced().getZ(),
                e.getPlayer().getUniqueId(), e.getPlayer().getName());
        list.add(p);
        save();
        e.getPlayer().sendMessage(msg("protect-placed", "{radius}",
                String.valueOf(cfg().getInt("settings.radius-chunks", 1))));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();

        Protector self = at(b);
        if (self != null) {
            if (p.hasPermission("falixcore.protect.bypass")) {
                list.remove(self);
                save();
                p.sendMessage(msg("protect-removed"));
            } else {
                e.setCancelled(true);
                p.sendMessage(msg("protect-blocked"));
            }
            return;
        }
        if (blocking(b, p) != null) {
            e.setCancelled(true);
            p.sendMessage(msg("protect-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!cfg().getBoolean("settings.protect-explosions", true)) return;
        e.blockList().removeIf(b -> blocking(b, null) != null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!cfg().getBoolean("settings.protect-explosions", true)) return;
        e.blockList().removeIf(b -> blocking(b, null) != null);
    }
}
