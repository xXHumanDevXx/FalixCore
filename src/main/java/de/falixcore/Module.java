package de.falixcore;

import org.bukkit.command.CommandSender;

import java.util.List;

/** Ein Funktionsbereich von FalixCore (z. B. Teleport oder Docs). */
public interface Module {

    String name();

    /** Alle Befehle, die dieses Modul bearbeitet. */
    List<String> commands();

    void enable();

    void disable();

    void reload();

    boolean handle(CommandSender s, String command, String[] args);

    List<String> complete(CommandSender s, String command, String[] args);
}
