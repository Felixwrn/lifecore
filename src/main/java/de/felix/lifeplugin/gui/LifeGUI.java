package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class LifeGUI {

    public static String getTitle(Player p) {
        return Main.getInstance()
                .getLanguageManager()
                .get(p.getUniqueId(), "gui_life_title");
    }

    public static void open(Player p) {

        var lm = Main.getInstance().getLanguageManager();
        var uuid = p.getUniqueId();

        Inventory inv = Bukkit.createInventory(null, 27, getTitle(p));

        int lives = Main.getInstance().getLives(uuid);

        // 🔲 Rahmen
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fMeta = filler.getItemMeta();
        if (fMeta != null) {
            fMeta.setDisplayName(" ");
            filler.setItemMeta(fMeta);
        }

        for (int i = 0; i < 27; i++) {
            if (i < 9 || i >= 18 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, filler);
            }
        }

        // ❤️ Herz Item
        ItemStack heart = new ItemStack(Material.RED_DYE);
        ItemMeta meta = heart.getItemMeta();

        if (meta == null) return;

        meta.setDisplayName("§c❤ §f§l" + lm.get(uuid, "gui_lives_title"));

        meta.setLore(List.of(
                "§7" + lm.get(uuid, "gui_current"),
                "§a§l" + lives,
                "",
                "§8» Life System"
        ));

        // ✨ Glint (nur optisch)
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        heart.setItemMeta(meta);

        // 🎯 Zentrum
        inv.setItem(13, heart);

        // ➕ Deko Items
        inv.setItem(11, createGlass(Material.RED_STAINED_GLASS_PANE));
        inv.setItem(15, createGlass(Material.RED_STAINED_GLASS_PANE));

        p.openInventory(inv);
    }

    private static ItemStack createGlass(Material mat) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }
}
