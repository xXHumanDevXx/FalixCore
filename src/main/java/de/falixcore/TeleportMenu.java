package de.falixcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Kisten-Menü für /warp und /home, gleiches Design wie alle anderen Menüs. */
public final class TeleportMenu {

    public enum Kind { WARP, HOME }

    /** Kennzeichnet unser Menü, damit Klicks eindeutig zugeordnet werden. */
    public static final class Holder implements InventoryHolder {
        private final Kind kind;
        private final Map<Integer, String> entries = new HashMap<>();
        private Inventory inventory;

        Holder(Kind kind) {
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }

        public Map<Integer, String> entries() {
            return entries;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private TeleportMenu() { }

    static void openWarps(TeleportModule m, Player p) {
        Map<String, Location> warps = m.store().warps();
        if (warps.isEmpty()) {
            p.sendMessage(m.msg("warp-none"));
            return;
        }
        build(m, p, Kind.WARP, warps);
    }

    static void openHomes(TeleportModule m, Player p) {
        Map<String, Location> homes = m.store().homes(p.getUniqueId());
        if (homes.isEmpty()) {
            p.sendMessage(m.msg("home-none"));
            return;
        }
        build(m, p, Kind.HOME, homes);
    }

    private static void build(TeleportModule m, Player p, Kind kind, Map<String, Location> entries) {
        YamlConfiguration cfg = m.cfg();
        FalixCore core = m.core();

        Holder holder = new Holder(kind);
        String titleRaw = cfg.getString(kind == Kind.WARP ? "ui.warp-title" : "ui.home-title", "Menü");
        Inventory inv = Bukkit.createInventory(holder, 54, Ui.color("&8» " + titleRaw));
        holder.inventory = inv;

        ItemStack filler = named(material(core.getConfig().getString("ui.filler"),
                Material.GRAY_STAINED_GLASS_PANE), " ");
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        Material icon = material(cfg.getString(kind == Kind.WARP ? "ui.warp-icon" : "ui.home-icon"),
                kind == Kind.WARP ? Material.ENDER_PEARL : Material.RED_BED);

        int slot = 0;
        for (Map.Entry<String, Location> en : entries.entrySet()) {
            if (slot >= 45) break;
            Location l = en.getValue();

            List<String> lore = new ArrayList<>();
            lore.add(Ui.color("&8Welt: &7" + l.getWorld().getName()));
            lore.add(Ui.color("&8Ort: &7" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
            lore.add("");
            lore.add(Ui.color("&aLinksklick: &7teleportieren"));
            if (kind == Kind.HOME) lore.add(Ui.color("&cRechtsklick: &7löschen"));

            ItemStack item = new ItemStack(icon);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(Ui.color("&b" + en.getKey()));
            meta.setLore(lore);
            item.setItemMeta(meta);

            inv.setItem(slot, item);
            holder.entries.put(slot, en.getKey());
            slot++;
        }
        p.openInventory(inv);
    }

    private static ItemStack named(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        it.setItemMeta(meta);
        return it;
    }

    private static Material material(String name, Material def) {
        Material mt = Material.matchMaterial(name == null ? "" : name);
        return mt == null ? def : mt;
    }
}
