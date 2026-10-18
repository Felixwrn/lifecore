package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import org.bukkit.entity.Player;

public final class LanguageGUI {
    public static final String TITLE = "§6Language";
    private LanguageGUI() { }
    public static void open(Player player) {
        Main plugin = Main.getInstance();
        if (plugin != null && plugin.getMenus() != null) plugin.getMenus().openLanguages(player);
    }
}
