package de.felix.lifeplugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class LifeGUI {

    public static void open(Player p) {

        Inventory inv = Bukkit.createInventory(null, 27, "§cDeine Leben");

        int lives = Main.getInstance().getLives(p.getUniqueId());

        ItemStack heart = new ItemStack(Material.RED_DYE);
        ItemMeta meta = heart.getItemMeta();

        meta.setDisplayName("§cLeben");
        meta.setLore(List.of(
                "§7Aktuell:",
                "§a" + lives
        ));

        heart.setItemMeta(meta);

        inv.setItem(13, heart);

        p.openInventory(inv);
    }
}
