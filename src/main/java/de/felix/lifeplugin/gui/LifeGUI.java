package de.felix.lifeplugin.gui;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class LifeGUI {

    public static String getTitle(Player p) {
        return "§cLives";
    }

    public static void open(Player p) {

        Inventory inv = Bukkit.createInventory(null, 27, getTitle(p));

        ItemStack heart = new ItemStack(Material.RED_DYE);
        ItemMeta meta = heart.getItemMeta();

        if (meta == null) return;

        meta.setDisplayName("§cLives");
        meta.setLore(List.of("§7Current lives"));

        // ✨ Glint FIX
        meta.addEnchant(org.bukkit.enchantments.Enchantment.getByName("UNBREAKING"), 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        heart.setItemMeta(meta);

        inv.setItem(13, heart);

        p.openInventory(inv);
    }
}
