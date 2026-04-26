package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.*;

public class LanguageGUI {

    private static final String TITLE = "§6§lLanguage Selection";

    public static String getTitle() {
        return TITLE;
    }

    public static void open(Player p) {

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

        // 📍 Slots
        int[] slots = {10,11,12,13,14,15,16};
        int index = 0;

        // 🌍 Flag URLs (Minecraft Heads)
        Map<String, String> flags = new HashMap<>();
        flags.put("de", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGRlYzA4Y2Y3YzFiNjY2YmM1NTRkN2Y3MzI1Y2Q4OTBiODc1YjMyZjA0NmU4OTk0ZTM3ZmY0ZmRjY2QifX19");
        flags.put("en", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZWM5OTkxOTk3ODMyY2NhYmU5N2RjMjBlY2QxNDRlZWI2OTg1OTUyM2RjMTRhMTlkMmJhOTQ3NjI5ZTcifX19");
        flags.put("fr", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzY1MjFhNmFlYjM1NzM3ZDA3YzM3YzI4ZTg0Njk4NjY5YjQ4MzI3NjlhZjljY2JjYzJhZjM2ZmIifX19");
        flags.put("es", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzM0Njk4ZjI0NTgxZmQzM2Q2NzI4NjQ4YzZlNmQ4NzYyYmY1NzI0ZTY3ZjQ5Mjk5NjZjZjQifX19");
        flags.put("it", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzA3NTU5Y2U4NjI1MjgxNzYyZjI5NjY5ZTk5Y2M1YjYzODQ1NDgyZmUifX19");

        // 🔧 Methode für Skull
        for (String lang : getAllLanguages()) {

            boolean installed = new File(
                    Main.getInstance().getDataFolder(),
                    "lang/" + lang + ".json"
            ).exists();

            boolean isCurrent = lang.equalsIgnoreCase(current);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();

            if (meta == null) continue;

            // Texture setzen (nur wenn vorhanden)
            if (flags.containsKey(lang)) {
                meta.setOwnerProfile(Bukkit.createPlayerProfile(UUID.randomUUID()));
                meta.getOwnerProfile().getTextures().setSkin(
                        java.net.URI.create("http://textures.minecraft.net/texture/" + flags.get(lang))
                );
            }

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

            head.setItemMeta(meta);

            if (index < slots.length) {
                inv.setItem(slots[index++], head);
            }
        }

        p.openInventory(inv);
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
}
