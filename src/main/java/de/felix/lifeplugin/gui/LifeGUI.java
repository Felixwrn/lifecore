package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

public class LifeGUI {
    public static final String TITLE = "§cLives ❤ ";

    public static void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);
        int lives = Main.getInstance().getConfig().getInt("lives." + p.getUniqueId(), 3);

        inv.setItem(11, item(Material.GREEN_WOOL, "§a+1 Life"));
        inv.setItem(13, item(Material.PAPER, "§eLives: " + lives));
        inv.setItem(15, item(Material.RED_WOOL, "§c-1 Life"));

        p.openInventory(inv);
    }

    private static ItemStack item(Material m, String name) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        meta.setDisplayName(name);
        i.setItemMeta(meta);
        return i;
    }
}
