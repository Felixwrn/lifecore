package de.felix.lifeplugin.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ModeGUI {

    public static final String TITLE = "§bMode";

    public static void open(Player p) {

        Inventory inv = Bukkit.createInventory(null, 27, TITLE);

        inv.setItem(11, createItem(Material.REDSTONE, "§cHardcore Mode"));
        inv.setItem(15, createItem(Material.EMERALD, "§aNormal Mode"));

        p.openInventory(inv);
    }

    private static ItemStack createItem(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }

        return item;
    }
}
