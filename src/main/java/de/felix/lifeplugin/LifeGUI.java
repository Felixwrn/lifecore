package de.felix.lifeplugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;

public class LifeGUI {

    public static void open(Player p) {

        Inventory inv = Bukkit.createInventory(null, 27, "§cDeine Leben");

        int lives = Main.getInstance().getLives(p.getUniqueId());

        // ❤️ Anzeige Item
        ItemStack heart = new ItemStack(Material.RED_DYE);
        ItemMeta meta = heart.getItemMeta();

        meta.setDisplayName("§cLeben");
        meta.setLore(java.util.List.of(
                "§7Du hast aktuell:",
                "§a" + lives + " §7Leben"
        ));

        heart.setItemMeta(meta);

        inv.setItem(13, heart);

        // Info Item
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta meta2 = info.getItemMeta();

        meta2.setDisplayName("§eInfo");
        meta2.setLore(java.util.List.of(
                "§7Bei 0 Leben wirst du gekickt",
                "§7Sterben reduziert Leben"
        ));

        info.setItemMeta(meta2);

        inv.setItem(11, info);

        p.openInventory(inv);
    }
}
