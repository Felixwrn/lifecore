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

    private static final String TITLE = "§6§lLanguage Selection";

    private static final Map<UUID, Integer> pages = new HashMap<>();
    private static final int ITEMS_PER_PAGE = 14;

    public static String getTitle() {
        return TITLE;
    }

    public static void open(Player p) {
        open(p, pages.getOrDefault(p.getUniqueId(), 0));
    }

    public static void open(Player p, int page) {

        pages.put(p.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, 27, TITLE);

        String current = Main.getInstance()
                .getLanguageManager()
                .getLanguage(p.getUniqueId());

        // 🔲 Rahmen
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fMeta = filler.getItemMeta();
        if (fMeta != null) {
            fMeta.setDisplayName(" ");
            filler.setItemMeta(fMeta);
        }

        for (int i = 0; i < 27; i++) {
            if (i < 9 || i > 17 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, filler);
            }
        }

        int[] slots = {
                10,11,12,13,14,15,16,
                19,20,21,22,23,24,25
        };

        List<String> langs = getAllLanguages();
        Collections.sort(langs);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, langs.size());

        int index = 0;

        for (int i = start; i < end; i++) {

            String lang = langs.get(i);

            boolean installed = new File(
                    Main.getInstance().getDataFolder(),
                    "lang/" + lang + ".json"
            ).exists();

            boolean isCurrent = lang.equalsIgnoreCase(current);

            // 🧱 Item (ohne Flag → simple)
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
                            ? "§a✔ Selected"
                            : installed
                                ? "§e✔ Installed"
                                : "§c✖ Not installed",
                    "",
                    installed
                            ? "§eClick to select"
                            : "§cClick to download"
            ));

            item.setItemMeta(meta);

            if (index < slots.length) {
                inv.setItem(slots[index++], item);
            }
        }

        // ⬅ Back
        if (page > 0) {
            inv.setItem(18, createButton(Material.ARROW, "§c← Previous"));
        }

        // ➡ Next
        if (end < langs.size()) {
            inv.setItem(26, createButton(Material.ARROW, "§aNext →"));
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
