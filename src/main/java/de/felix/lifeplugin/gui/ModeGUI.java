package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import org.bukkit.entity.Player;

public final class ModeGUI {
    public static final String TITLE = "§bMode";
    private ModeGUI() { }
    public static void open(Player player) {
        Main plugin = Main.getInstance();
        if (plugin != null && plugin.getMenus() != null) plugin.getMenus().openModes(player);
    }
}
