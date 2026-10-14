package de.falixcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Modul Teleport: Anfragen, Wartezeit, Warps, Homes, Menüs und Party. */
public class TeleportModule implements Module, Listener {

    private record Request(UUID requester, UUID mover, UUID dest, long time) { }

    private record Pending(BukkitTask task, Location start) { }

    private static final class Party {
        final UUID leader;
        final Set<UUID> members = new LinkedHashSet<>();

        Party(UUID leader) {
            this.leader = leader;
            members.add(leader);
        }
    }

    private final FalixCore core;
    private final TeleportStore store;
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, UUID> invites = new HashMap<>();

    public TeleportModule(FalixCore core) {
        this.core = core;
        this.store = new TeleportStore(core);
    }

    // ------------------------------------------------------------------
    //  Modul-Grundlagen
    // ------------------------------------------------------------------

    @Override
    public String name() {
        return "teleport";
    }

    @Override
    public List<String> commands() {
        return List.of("tpa", "tpaccept", "tpdeny", "tphere", "party", "warp", "setwarp", "delwarp",
                "sethome", "home", "delhome", "fav");
    }

    @Override
    public void enable() {
        store.load();
        core.getServer().getPluginManager().registerEvents(this, core);
    }

    @Override
    public void disable() {
        store.save();
        for (UUID id : new ArrayList<>(pending.keySet())) {
            cancel(id);
        }
    }

    @Override
    public void reload() {
        // Config wird bei jedem Zugriff frisch gelesen, nichts weiter nötig
    }

    YamlConfiguration cfg() {
        return core.cfg("teleport");
    }

    FalixCore core() {
        return core;
    }

    TeleportStore store() {
        return store;
    }

    String msg(String key, String... pairs) {
        return Ui.msg(core, cfg(), key, pairs);
    }

    // ------------------------------------------------------------------
    //  Befehle
    // ------------------------------------------------------------------

    @Override
    public boolean handle(CommandSender s, String command, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage(msg("player-only"));
            return true;
        }
        switch (command) {
            case "tpa" -> tpa(p, a, false);
            case "tphere" -> {
                if (!p.hasPermission("falixcore.admin")) p.sendMessage(msg("no-permission"));
                else tpa(p, a, true);
            }
            case "tpaccept" -> accept(p);
            case "tpdeny" -> deny(p);
            case "party" -> party(p, a);
            case "warp" -> {
                if (a.length == 0) TeleportMenu.openWarps(this, p);
                else warp(p, a[0]);
            }
            case "setwarp" -> setWarp(p, a);
            case "delwarp" -> delWarp(p, a);
            case "sethome" -> setHome(p, a);
            case "home" -> {
                if (a.length == 0) TeleportMenu.openHomes(this, p);
                else home(p, a[0]);
            }
            case "delhome" -> delHome(p, a);
            case "fav" -> fav(p, a);
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> complete(CommandSender s, String command, String[] a) {
        if (!(s instanceof Player p) || a.length == 0) return List.of();
        List<String> options = new ArrayList<>();
        if (a.length == 1) {
            switch (command) {
                case "party" -> options.addAll(List.of("invite", "accept", "leave", "kick", "disband", "list"));
                case "tpa", "tphere" -> Bukkit.getOnlinePlayers().forEach(x -> options.add(x.getName()));
                case "warp", "delwarp" -> options.addAll(store.warps().keySet());
                case "home", "delhome" -> options.addAll(store.homes(p.getUniqueId()).keySet());
                default -> { }
            }
        } else if (a.length == 2 && command.equals("party")
                && (a[0].equalsIgnoreCase("invite") || a[0].equalsIgnoreCase("kick"))) {
            Bukkit.getOnlinePlayers().forEach(x -> options.add(x.getName()));
        }
        List<String> out = new ArrayList<>();
        String start = a[a.length - 1].toLowerCase(Locale.ROOT);
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(start)) out.add(o);
        }
        return out;
    }

    private void tpa(Player p, String[] a, boolean here) {
        if (a.length < 1) {
            p.sendMessage(msg("usage", "{usage}", here ? "/tphere <Spieler>" : "/tpa <Spieler>"));
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            p.sendMessage(msg("player-offline", "{player}", a[0]));
            return;
        }
        if (t.equals(p)) {
            p.sendMessage(msg("self-target"));
            return;
        }
        request(p, t, here);
    }

    private void request(Player from, Player to, boolean here) {
        UUID mover = here ? to.getUniqueId() : from.getUniqueId();
        UUID dest = here ? from.getUniqueId() : to.getUniqueId();
        requests.put(to.getUniqueId(), new Request(from.getUniqueId(), mover, dest, System.currentTimeMillis()));

        if (here) {
            to.sendMessage(msg("tphere-received", "{player}", from.getName()));
            from.sendMessage(msg("tphere-sent", "{player}", to.getName()));
        } else {
            to.sendMessage(msg("tpa-received", "{player}", from.getName()));
            from.sendMessage(msg("tpa-sent", "{player}", to.getName()));
        }
        to.sendMessage(msg("request-hint"));
    }

    private void accept(Player target) {
        Request r = requests.remove(target.getUniqueId());
        if (r == null) {
            target.sendMessage(msg("request-none"));
            return;
        }
        long timeout = cfg().getLong("settings.request-timeout", 60) * 1000L;
        if (System.currentTimeMillis() - r.time() > timeout) {
            target.sendMessage(msg("request-expired"));
            return;
        }
        Player requester = Bukkit.getPlayer(r.requester());
        Player mover = Bukkit.getPlayer(r.mover());
        Player dest = Bukkit.getPlayer(r.dest());
        if (mover == null || dest == null) {
            target.sendMessage(msg("request-expired"));
            return;
        }
        if (requester != null) {
            requester.sendMessage(msg("request-accepted", "{player}", target.getName()));
        }
        teleportDelayed(mover, dest.getLocation(), "teleported-to", "{player}", dest.getName());
    }

    private void deny(Player target) {
        Request r = requests.remove(target.getUniqueId());
        if (r == null) {
            target.sendMessage(msg("request-none"));
            return;
        }
        Player requester = Bukkit.getPlayer(r.requester());
        if (requester != null) {
            requester.sendMessage(msg("request-denied", "{player}", target.getName()));
        }
        target.sendMessage(msg("request-denied-self"));
    }

    // ------------------------------------------------------------------
    //  Warps und Homes
    // ------------------------------------------------------------------

    private void warp(Player p, String name) {
        Location l = store.getWarp(name);
        if (l == null) {
            p.sendMessage(msg("warp-not-found", "{name}", name));
            return;
        }
        teleportDelayed(p, l, "teleported-to-warp", "{name}", name);
    }

    private void home(Player p, String name) {
        Location l = store.getHome(p.getUniqueId(), name);
        if (l == null) {
            p.sendMessage(msg("home-not-found", "{name}", name));
            return;
        }
        teleportDelayed(p, l, "teleported-home", "{name}", name);
    }

    private void setWarp(Player p, String[] a) {
        if (!p.hasPermission("falixcore.admin")) {
            p.sendMessage(msg("no-permission"));
            return;
        }
        if (a.length < 1) {
            p.sendMessage(msg("usage", "{usage}", "/setwarp <Name>"));
            return;
        }
        store.setWarp(a[0], p.getLocation());
        p.sendMessage(msg("warp-set", "{name}", a[0].toLowerCase(Locale.ROOT)));
    }

    private void delWarp(Player p, String[] a) {
        if (!p.hasPermission("falixcore.admin")) {
            p.sendMessage(msg("no-permission"));
            return;
        }
        if (a.length < 1) {
            p.sendMessage(msg("usage", "{usage}", "/delwarp <Name>"));
            return;
        }
        if (store.delWarp(a[0])) {
            p.sendMessage(msg("warp-deleted", "{name}", a[0].toLowerCase(Locale.ROOT)));
        } else {
            p.sendMessage(msg("warp-not-found", "{name}", a[0]));
        }
    }

    private void setHome(Player p, String[] a) {
        String name = a.length > 0 ? a[0] : "home";
        int max = maxHomes(p);
        boolean exists = store.getHome(p.getUniqueId(), name) != null;
        boolean unlimited = p.hasPermission("falixcore.admin");
        if (!exists && !unlimited && store.homes(p.getUniqueId()).size() >= max) {
            p.sendMessage(msg("home-limit", "{max}", String.valueOf(max)));
            return;
        }
        store.setHome(p.getUniqueId(), name, p.getLocation());
        p.sendMessage(msg("home-set", "{name}", name.toLowerCase(Locale.ROOT)));
    }

    private void delHome(Player p, String[] a) {
        if (a.length < 1) {
            p.sendMessage(msg("usage", "{usage}", "/delhome <Name>"));
            return;
        }
        if (store.delHome(p.getUniqueId(), a[0])) {
            p.sendMessage(msg("home-deleted", "{name}", a[0].toLowerCase(Locale.ROOT)));
        } else {
            p.sendMessage(msg("home-not-found", "{name}", a[0]));
        }
    }

    /** Markiert ein Home als Favorit. Nur mit aktivem Ultimate, bis zu 4 Stück. */
    private void fav(Player p, String[] a) {
        if (a.length < 1) {
            p.sendMessage(msg("usage", "{usage}", "/fav <Home-Name>"));
            return;
        }
        if (core.ultimate() == null || !core.ultimate().isActive(p.getUniqueId())) {
            p.sendMessage(msg("fav-not-ultimate"));
            return;
        }
        if (store.getHome(p.getUniqueId(), a[0]) == null) {
            p.sendMessage(msg("home-not-found", "{name}", a[0]));
            return;
        }
        if (!store.isFavourite(p.getUniqueId(), a[0]) && store.favouriteCount(p.getUniqueId()) >= 4) {
            p.sendMessage(msg("fav-limit"));
            return;
        }
        boolean nowFav = store.toggleFavourite(p.getUniqueId(), a[0]);
        p.sendMessage(msg(nowFav ? "fav-added" : "fav-removed", "{name}", a[0].toLowerCase(Locale.ROOT)));
    }

    /** Standard aus der config, oder die höchste Zahl aus dem Recht falixcore.homes.<n>. */
    private int maxHomes(Player p) {
        int best = cfg().getInt("settings.max-homes", 3);
        for (int n = 1; n <= 50; n++) {
            if (p.hasPermission("falixcore.homes." + n)) best = Math.max(best, n);
        }
        return best;
    }

    // ------------------------------------------------------------------
    //  Wartezeit
    // ------------------------------------------------------------------

    void teleportDelayed(Player p, Location dest, String key, String... pairs) {
        cancel(p.getUniqueId());
        int delay = cfg().getInt("settings.teleport-delay", 3);
        if (delay <= 0) {
            p.teleport(dest);
            p.sendMessage(msg(key, pairs));
            return;
        }
        UUID id = p.getUniqueId();
        p.sendMessage(msg("teleport-delay", "{seconds}", String.valueOf(delay)));
        Location start = p.getLocation().clone();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(core, () -> {
            pending.remove(id);
            Player online = Bukkit.getPlayer(id);
            if (online == null) return;
            online.teleport(dest);
            online.sendMessage(msg(key, pairs));
        }, delay * 20L);
        pending.put(id, new Pending(task, start));
    }

    private void cancel(UUID id) {
        Pending pd = pending.remove(id);
        if (pd != null) pd.task().cancel();
    }

    private void forget(UUID id) {
        cancel(id);
        requests.remove(id);
        requests.entrySet().removeIf(en -> en.getValue().requester().equals(id));
    }

    // ------------------------------------------------------------------
    //  Party
    // ------------------------------------------------------------------

    private void party(Player p, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "invite" -> {
                if (a.length < 2) {
                    p.sendMessage(msg("usage", "{usage}", "/party invite <Spieler>"));
                    return;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    p.sendMessage(msg("player-offline", "{player}", a[1]));
                    return;
                }
                partyInvite(p, t);
            }
            case "accept" -> partyAccept(p);
            case "leave" -> partyLeave(p);
            case "kick" -> {
                if (a.length < 2) {
                    p.sendMessage(msg("usage", "{usage}", "/party kick <Spieler>"));
                    return;
                }
                partyKick(p, a[1]);
            }
            case "disband" -> partyDisband(p);
            case "list" -> partyList(p);
            default -> p.sendMessage(msg("usage", "{usage}", "/party <invite | accept | leave | kick | disband | list>"));
        }
    }

    private void partyInvite(Player leader, Player target) {
        if (leader.getUniqueId().equals(target.getUniqueId())) {
            leader.sendMessage(msg("party-self"));
            return;
        }
        Party party = parties.get(leader.getUniqueId());
        if (party == null) {
            party = new Party(leader.getUniqueId());
            parties.put(leader.getUniqueId(), party);
        } else if (!party.leader.equals(leader.getUniqueId())) {
            leader.sendMessage(msg("party-not-leader"));
            return;
        }
        if (parties.containsKey(target.getUniqueId())) {
            leader.sendMessage(msg("party-already", "{player}", target.getName()));
            return;
        }
        invites.put(target.getUniqueId(), leader.getUniqueId());
        target.sendMessage(msg("party-invited", "{player}", leader.getName()));
        leader.sendMessage(msg("party-invite-sent", "{player}", target.getName()));
    }

    private void partyAccept(Player p) {
        UUID leaderId = invites.remove(p.getUniqueId());
        if (leaderId == null) {
            p.sendMessage(msg("party-no-invite"));
            return;
        }
        if (parties.containsKey(p.getUniqueId())) {
            p.sendMessage(msg("party-already-in"));
            return;
        }
        Party party = parties.get(leaderId);
        if (party == null) {
            p.sendMessage(msg("party-gone"));
            return;
        }
        party.members.add(p.getUniqueId());
        parties.put(p.getUniqueId(), party);
        notifyParty(party, "party-joined", "{player}", p.getName());
    }

    private void partyLeave(Player p) {
        Party party = parties.get(p.getUniqueId());
        if (party == null) {
            p.sendMessage(msg("party-not-in"));
            return;
        }
        if (party.leader.equals(p.getUniqueId())) {
            dissolve(party);
            return;
        }
        party.members.remove(p.getUniqueId());
        parties.remove(p.getUniqueId());
        p.sendMessage(msg("party-you-left"));
        notifyParty(party, "party-left", "{player}", p.getName());
    }

    private void partyKick(Player leader, String name) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null) {
            leader.sendMessage(msg("party-not-in"));
            return;
        }
        if (!party.leader.equals(leader.getUniqueId())) {
            leader.sendMessage(msg("party-not-leader"));
            return;
        }
        UUID target = null;
        for (UUID id : party.members) {
            if (memberName(id).equalsIgnoreCase(name)) {
                target = id;
                break;
            }
        }
        if (target == null || target.equals(party.leader)) {
            leader.sendMessage(msg("player-not-found", "{player}", name));
            return;
        }
        party.members.remove(target);
        parties.remove(target);
        Player kicked = Bukkit.getPlayer(target);
        if (kicked != null) kicked.sendMessage(msg("party-you-kicked"));
        notifyParty(party, "party-kicked", "{player}", memberName(target));
    }

    private void partyDisband(Player leader) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null) {
            leader.sendMessage(msg("party-not-in"));
            return;
        }
        if (!party.leader.equals(leader.getUniqueId())) {
            leader.sendMessage(msg("party-not-leader"));
            return;
        }
        dissolve(party);
    }

    private void partyList(Player p) {
        Party party = parties.get(p.getUniqueId());
        if (party == null) {
            p.sendMessage(msg("party-none"));
            return;
        }
        p.sendMessage(msg("party-list-header",
                "{count}", String.valueOf(party.members.size()),
                "{leader}", memberName(party.leader)));
        for (UUID id : party.members) {
            p.sendMessage(msg("party-list-entry", "{player}", memberName(id)));
        }
    }

    private void dissolve(Party party) {
        notifyParty(party, "party-disbanded");
        for (UUID id : party.members) {
            parties.remove(id);
        }
        party.members.clear();
    }

    private void notifyParty(Party party, String key, String... pairs) {
        for (UUID id : party.members) {
            Player m = Bukkit.getPlayer(id);
            if (m != null) m.sendMessage(msg(key, pairs));
        }
    }

    private static String memberName(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : "?";
    }

    // ------------------------------------------------------------------
    //  Listener
    // ------------------------------------------------------------------

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Pending pd = pending.get(e.getPlayer().getUniqueId());
        if (pd == null) return;
        Location s = pd.start();
        if (s.getWorld() == to.getWorld() && s.getBlockX() == to.getBlockX()
                && s.getBlockY() == to.getBlockY() && s.getBlockZ() == to.getBlockZ()) return;
        cancel(e.getPlayer().getUniqueId());
        e.getPlayer().sendMessage(msg("teleport-cancelled"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        forget(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof TeleportMenu.Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;

        String name = holder.entries().get(e.getSlot());
        if (name == null) return;

        if (holder.kind() == TeleportMenu.Kind.HOME && e.isRightClick()) {
            store.delHome(p.getUniqueId(), name);
            p.sendMessage(msg("home-deleted", "{name}", name));
            TeleportMenu.openHomes(this, p);
            return;
        }
        if (!e.isLeftClick()) return;

        p.closeInventory();
        if (holder.kind() == TeleportMenu.Kind.WARP) {
            warp(p, name);
        } else {
            home(p, name);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof TeleportMenu.Holder) {
            e.setCancelled(true);
        }
    }
}
