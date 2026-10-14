package de.falixcore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Modul Menü: ein zentrales Menü mit Knöpfen für alle Funktionen. Gleiches Design wie die übrigen Menüs. */
public class MenuModule implements Module, Listener {

    private static final class Holder implements InventoryHolder {
        final Map<Integer, String> actions = new HashMap<>();
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final FalixCore core;

    public MenuModule(FalixCore core) {
        this.core = core;
    }

    @Override
    public String name() {
        return "menu";
    }

    @Override
    public List<String> commands() {
        return List.of("menu");
    }

    @Override
    public void enable() {
        core.getServer().getPluginManager().registerEvents(this, core);
    }

    @Override
    public void disable() {
        // Nichts zu speichern
    }

    @Override
    public void reload() {
        // Config wird bei jedem Öffnen frisch gelesen
    }

    private YamlConfiguration cfg() {
        return core.cfg("menu");
    }

    @Override
    public boolean handle(CommandSender s, String command, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Ui.msg(core, cfg(), "player-only"));
            return true;
        }
        open(p);
        return true;
    }

    @Override
    public List<String> complete(CommandSender s, String command, String[] a) {
        return new ArrayList<>();
    }

    private void open(Player p) {
        YamlConfiguration c = cfg();
        int rows = Math.max(1, Math.min(6, c.getInt("rows", 3)));
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, rows * 9, Ui.color(c.getString("title", "&8» &bMenü")));
        holder.inventory = inv;

        ItemStack filler = named(material(c.getString("filler", "GRAY_STAINED_GLASS_PANE"),
                Material.GRAY_STAINED_GLASS_PANE), " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        for (Map<?, ?> m : c.getMapList("items")) {
            Object slotObj = m.get("slot");
            if (!(slotObj instanceof Number)) continue;
            int slot = ((Number) slotObj).intValue();
            if (slot < 0 || slot >= inv.getSize()) continue;

            Material mat = material(String.valueOf(m.get("material")), Material.PAPER);
            List<String> lore = new ArrayList<>();
            Object loreObj = m.get("lore");
            if (loreObj instanceof List<?> list) {
                for (Object line : list) lore.add(Ui.color(String.valueOf(line)));
            }
            String name = m.get("name") == null ? "" : String.valueOf(m.get("name"));
            inv.setItem(slot, named(mat, Ui.color(name), lore));
            if (m.get("command") != null) {
                holder.actions.put(slot, String.valueOf(m.get("command")));
            }
        }
        p.openInventory(inv);
    }

    private static ItemStack named(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static Material material(String name, Material def) {
        Material mt = Material.matchMaterial(name == null ? "" : name);
        return mt == null ? def : mt;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        String command = holder.actions.get(e.getSlot());
        if (command == null || command.isBlank()) return;
        p.closeInventory();
        p.performCommand(command);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
