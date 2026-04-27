package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import de.felix.lifeplugin.util.ChatInput;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public class ModeBuilderGUI {

    private static final Map<UUID, CustomMode> editing = new HashMap<>();
    private static final Map<UUID, String> names = new HashMap<>();

    public static void open(Player p, String name) {

        editing.putIfAbsent(p.getUniqueId(), new CustomMode());
        names.put(p.getUniqueId(), name);

        CustomMode m = editing.get(p.getUniqueId());

        Inventory inv = Bukkit.createInventory(null, 27, "§6Mode Builder: " + name);

        inv.setItem(10, item(toggle(m.stealOnKill), "Steal", ""+m.stealOnKill));
        inv.setItem(11, item(Material.GREEN_STAINED_GLASS_PANE, "+", ""+m.stealAmount));
        inv.setItem(12, item(Material.RED_STAINED_GLASS_PANE, "-", ""+m.stealAmount));
        inv.setItem(13, item(Material.HEART_OF_THE_SEA, "Max", ""+m.maxLives));
        inv.setItem(14, item(toggle(m.loseOnDeath), "Lose", ""+m.loseOnDeath));

        inv.setItem(15, item(Material.valueOf(m.icon), "Icon", m.icon));
        inv.setItem(16, item(Material.PAPER, "Desc", m.description));

        inv.setItem(22, item(Material.EMERALD_BLOCK, "SAVE", "click"));

        p.openInventory(inv);
    }

    public static void click(Player p, int slot) {

        CustomMode m = editing.get(p.getUniqueId());
        String name = names.get(p.getUniqueId());

        if (m == null) return;

        switch (slot) {
            case 10 -> m.stealOnKill = !m.stealOnKill;
            case 11 -> m.stealAmount++;
            case 12 -> m.stealAmount = Math.max(0, m.stealAmount-1);
            case 13 -> m.maxLives += 5;
            case 14 -> m.loseOnDeath = !m.loseOnDeath;

            case 15 -> {
                Material[] mats = {Material.DIAMOND, Material.NETHER_STAR, Material.BLAZE_ROD};
                int i=0;
                for(int x=0;x<mats.length;x++) if(mats[x].name().equals(m.icon)) i=x;
                m.icon = mats[(i+1)%mats.length].name();
            }

            case 16 -> {
                p.closeInventory();
                p.sendMessage("Type desc...");
                ChatInput.wait(p, t -> {
                    m.description = t;
                    open(p,name);
                });
                return;
            }

            case 22 -> {
                save(name,m);
                p.sendMessage("Saved!");
                p.closeInventory();
                return;
            }
        }

        open(p,name);
    }

    private static void save(String name, CustomMode m){
        try{
            File f = new File(Main.getInstance().getDataFolder(),"modes/"+name+".yml");
            YamlConfiguration y = new YamlConfiguration();
            y.set("mode.steal-on-kill", m.stealOnKill);
            y.set("mode.steal-amount", m.stealAmount);
            y.set("mode.max-lives", m.maxLives);
            y.set("mode.icon", m.icon);
            y.set("mode.description", m.description);
            y.save(f);
        }catch(Exception e){e.printStackTrace();}
    }

    private static Material toggle(boolean b){ return b?Material.LIME_DYE:Material.GRAY_DYE; }

    private static ItemStack item(Material m,String n,String l){
        ItemStack i=new ItemStack(m);
        ItemMeta meta=i.getItemMeta();
        meta.setDisplayName(n);
        meta.setLore(List.of(l));
        i.setItemMeta(meta);
        return i;
    }

    public static class CustomMode{
        boolean stealOnKill=true;
        int stealAmount=1;
        int maxLives=20;
        boolean loseOnDeath=true;
        String icon="DIAMOND";
        String description="None";
    }
}
