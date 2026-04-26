package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.List;
import java.util.Map;

public class LanguageGUI {

    private static final String TITLE = "§6Language Selection";

    public static String getTitle() {
        return TITLE;
    }

    public static void open(Player p) {

        Inventory inv = Bukkit.createInventory(null, 27, TITLE);

        String current = Main.getInstance()
                .getLanguageManager()
                .getLanguage(p.getUniqueId());

        int slot = 0;

        // 🔥 Default Sprachen (immer da)
        String[] defaults = {"de", "en"};

        for (String lang : defaults) {

            ItemStack item = new ItemStack(Material.LIME_DYE);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;

            boolean isCurrent = lang.equalsIgnoreCase(current);

            meta.setDisplayName("§e" + lang.toUpperCase());

            if (isCurrent) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            meta.setLore(List.of(
                    isCurrent
                            ? "§bCurrently selected"
                            : "§aInstalled (Click to use)"
            ));

            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        // 🌐 Config Sprachen
        if (Main.getInstance().getLangConfig().getConfigurationSection("languages") != null) {

            Map<String, Object> langs = Main.getInstance()
                    .getLangConfig()
                    .getConfigurationSection("languages")
                    .getValues(false);

            for (String lang : langs.keySet()) {

                boolean installed = new File(
                        Main.getInstance().getDataFolder(),
                        "lang/" + lang + ".json"
                ).exists();

                ItemStack item = new ItemStack(installed ? Material.LIME_DYE : Material.GRAY_DYE);
                ItemMeta meta = item.getItemMeta();
                if (meta == null) continue;

                boolean isCurrent = lang.equalsIgnoreCase(current);

                meta.setDisplayName("§e" + lang.toUpperCase());

                if (isCurrent) {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }

                meta.setLore(List.of(
                        isCurrent
                                ? "§bCurrently selected"
                                : installed
                                    ? "§aInstalled (Click to use)"
                                    : "§cNot installed (Click to download)"
                ));

                item.setItemMeta(meta);
                inv.setItem(slot++, item);
            }
        }

        p.openInventory(inv);
    }
}
