package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import de.felix.lifeplugin.mode.ModeDefinition;
import org.bukkit.entity.Player;

/** Compatibility entry point; interaction is handled by the owned menu listener. */
public final class ModeBuilderGUI {
    private ModeBuilderGUI() { }

    public static void open(Player player, String id) {
        Main plugin = Main.getInstance();
        if (plugin == null || plugin.getMenus() == null || !plugin.isReady()) return;
        try {
            ModeDefinition mode = plugin.getModes().find(id).orElseGet(() ->
                    new ModeDefinition(id, id, "", "DIAMOND", 3, 20, true, true, 1, false));
            plugin.getMenus().openBuilder(player, mode, plugin.getModes().find(id).isPresent());
        } catch (IllegalArgumentException failure) {
            player.sendMessage("§c" + failure.getMessage());
        }
    }

    /** Legacy slot callbacks cannot establish ownership and deliberately do nothing. */
    @Deprecated public static void click(Player player, int slot) { }
}
