package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.market.MarketplaceManager;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.json.JSONObject;

import java.util.*;

public class MarketplaceGUI {

    private static Map<String, JSONObject> cache = new HashMap<>();

    public static void reload(){
        cache = MarketplaceManager.load();
    }

    public static void open(Player p){
        reload();

        Inventory inv = Bukkit.createInventory(null,45,"§6Marketplace");

        int slot=10;

        for(String name:cache.keySet()){
            JSONObject o = cache.get(name);

            ItemStack item=new ItemStack(Material.BOOK);
            ItemMeta meta=item.getItemMeta();

            meta.setDisplayName("§e"+name);
            meta.setLore(List.of(
                    o.getString("description"),
                    "by "+o.getString("author"),
                    "click"
            ));

            item.setItemMeta(meta);
            inv.setItem(slot++,item);
        }

        p.openInventory(inv);
    }

    public static JSONObject get(String name){
        return cache.get(name);
    }
}
