package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class LifeGUI {

    private static final String TITLE = "§cDein Leben Menü";

    public static String getTitle() {
        return TITLE;
    }

    public static void open(Player p) {

        Inventory inv = Bukkit.createInventory(null, 27, TITLE);

        int lives = Main.getInstance().getLives(p.getUniqueId());

        ItemStack heart = new ItemStack(Material.RED_DYE);
        ItemMeta meta = heart.getItemMeta();

        if (meta == null) return;

        // 🌍 Name aus LanguageSystem
        meta.setDisplayName(
                Main.getInstance()
                        .getLanguageManager()
                        .get(p.getUniqueId(), "gui_lives_title")
        );

        // 🌍 Lore aus LanguageSystem
        meta.setLore(List.of(
                Main.getInstance()
                        .getLanguageManager()
                        .get(p.getUniqueId(), "gui_current"),
                "§a" + lives
        ));

        heart.setItemMeta(meta);

        inv.setItem(13, heart);

        p.openInventory(inv);
    }
}
