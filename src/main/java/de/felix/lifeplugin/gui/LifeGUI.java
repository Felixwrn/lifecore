package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import org.bukkit.entity.Player;

public final class LifeGUI {
    public static final String TITLE = "§cLives ❤ ";
    private LifeGUI() { }
    public static void open(Player player) {
        Main plugin = Main.getInstance();
        if (plugin != null && plugin.getMenus() != null) plugin.getMenus().openLives(player);
    }
}
