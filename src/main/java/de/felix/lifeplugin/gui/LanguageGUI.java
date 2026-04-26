package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;

public class LanguageGUI {

    private static final Map<UUID, Integer> pages = new HashMap<>();
    private static final int ITEMS_PER_PAGE = 21;

    public static String getTitle(Player p) {
        return Main.getInstance().getLanguageManager()
                .get(p.getUniqueId(), "langgui_title");
    }

    public static void open(Player p) {
        open(p, pages.getOrDefault(p.getUniqueId(), 0));
    }

    public static void open(Player p, int page) {

        pages.put(p.getUniqueId(), page);

        var lm = Main.getInstance().getLanguageManager();
        var uuid = p.getUniqueId();

        Inventory inv = Bukkit.createInventory(null, 45, getTitle(p));

        String current = lm.getLanguage(uuid);

        // Rahmen
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fMeta = filler.getItemMeta();
        if (fMeta != null) {
            fMeta.setDisplayName(" ");
            filler.setItemMeta(fMeta);
        }

        for (int i = 0; i < 45; i++) {
            if (i < 9 || i >= 36 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, filler);
            }
        }

        int[] slots = {
                10,11,12,13,14,15,16,
                19,20,21,22,23,24,25,
                28,29,30,31,32,33,34
        };

        List<String> all = getAllLanguages();

        List<String> sorted = new ArrayList<>();
        sorted.add("de");
        sorted.add("en");

        List<String> rest = new ArrayList<>(all);
        rest.removeIf(l -> l.equalsIgnoreCase("de") || l.equalsIgnoreCase("en"));
        rest.sort(String::compareToIgnoreCase);

        sorted.addAll(rest);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, sorted.size());

        int index = 0;

        for (int i = start; i < end; i++) {

            String lang = sorted.get(i);

            boolean installed = new File(
                    Main.getInstance().getDataFolder(),
                    "lang/" + lang + ".json"
            ).exists();

            boolean isCurrent = lang.equalsIgnoreCase(current);

            Material mat = installed ? Material.LIME_DYE : Material.GRAY_DYE;

            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;

            meta.setDisplayName(
                    isCurrent
                            ? "§6★ §f§l" + lang.toUpperCase()
                            : "§f§l" + lang.toUpperCase()
            );

            if (isCurrent) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            meta.setLore(List.of(
                    "§7Status:",
                    isCurrent
                            ? lm.get(uuid, "langgui_selected")
                            : installed
                                ? lm.get(uuid, "langgui_installed")
                                : lm.get(uuid, "langgui_not_installed"),
                    "",
                    installed
                            ? lm.get(uuid, "langgui_click_select")
                            : lm.get(uuid, "langgui_click_download")
            ));

            item.setItemMeta(meta);

            if (index < slots.length) {
                inv.setItem(slots[index++], item);
            }
        }

        if (page > 0) {
            inv.setItem(36, createButton(Material.ARROW, lm.get(uuid, "langgui_previous")));
        }

        if (end < sorted.size()) {
            inv.setItem(44, createButton(Material.ARROW, lm.get(uuid, "langgui_next")));
        }

        p.openInventory(inv);
    }

    private static ItemStack createButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static List<String> getAllLanguages() {

        List<String> langs = new ArrayList<>();
        langs.add("de");
        langs.add("en");

        if (Main.getInstance().getLangConfig().getConfigurationSection("languages") != null) {
            langs.addAll(
                    Main.getInstance()
                            .getLangConfig()
                            .getConfigurationSection("languages")
                            .getKeys(false)
            );
        }

        return langs;
    }

    public static int getPage(UUID uuid) {
        return pages.getOrDefault(uuid, 0);
    }
}
