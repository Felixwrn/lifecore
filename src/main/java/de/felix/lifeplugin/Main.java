package de.felix.lifeplugin;

import de.felix.lifeplugin.gui.LanguageGUI;
import de.felix.lifeplugin.gui.LifeGUI;
import de.felix.lifeplugin.gui.MarketplaceGUI;
import de.felix.lifeplugin.gui.ModeGUI;
import de.felix.lifeplugin.util.ActionBarUtil;
import de.wrn.api.api.WRNAPI;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;

public class Main extends JavaPlugin implements Listener {

    private static Main instance;

    public static Main getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {

        instance = this;

        saveDefaultConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        // ActionBar Loop
        Bukkit.getScheduler().runTaskTimer(this, () -> {

            for (Player p : Bukkit.getOnlinePlayers()) {

                int lives = getConfig().getInt(
                        "lives." + p.getUniqueId(),
                        getConfig().getInt("default-lives", 3)
                );

                String mode = getConfig().getString(
                        "player-mode." + p.getUniqueId(),
                        "normal"
                );

                ActionBarUtil.send(
                        p,
                        "§cLives: §f" + lives + " §7| §bMode: §f" + mode
                );
            }

        }, 0L, 40L);

        getLogger().info("§aLifePlugin enabled!");
    }

    @Override
    public void onDisable() {
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) {
            return true;
        }

        // Lives GUI
        if (cmd.getName().equalsIgnoreCase("livesgui")) {
            LifeGUI.open(p);
            return true;
        }

        // Language GUI
        if (cmd.getName().equalsIgnoreCase("langgui")) {
            LanguageGUI.open(p);
            return true;
        }

        // Mode GUI
        if (cmd.getName().equalsIgnoreCase("modegui")) {
            ModeGUI.open(p);
            return true;
        }

        // Marketplace
        if (cmd.getName().equalsIgnoreCase("market")) {
            MarketplaceGUI.open(p);
            return true;
        }

        return false;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {

        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }

        String title = e.getView().getTitle();

        // Cancel GUI interaction
        if (title.equals(LanguageGUI.TITLE)
                || title.equals(LifeGUI.TITLE)
                || title.equals(ModeGUI.TITLE)
                || title.equals(MarketplaceGUI.TITLE)) {

            e.setCancelled(true);
        }

        ItemStack item = e.getCurrentItem();

        if (item == null) {
            return;
        }

        if (!item.hasItemMeta()) {
            return;
        }

        // ---------------- LANGUAGE GUI ----------------

        if (title.equals(LanguageGUI.TITLE)) {

            String name = item.getItemMeta().getDisplayName();

            String lang = name.contains("(en)") ? "en" : "de";

            WRNAPI.setLanguage(p.getUniqueId(), lang);

            p.sendMessage("§aLanguage changed to §e" + lang);

            p.closeInventory();
        }

        // ---------------- LIFE GUI ----------------

        if (title.equals(LifeGUI.TITLE)) {

            int lives = getConfig().getInt(
                    "lives." + p.getUniqueId(),
                    getConfig().getInt("default-lives", 3)
            );

            // +1 Life
            if (e.getSlot() == 11) {
                lives++;
            }

            // -1 Life
            if (e.getSlot() == 15) {
                lives--;
            }

            // Prevent negative lives
            if (lives < 0) {
                lives = 0;
            }

            getConfig().set("lives." + p.getUniqueId(), lives);

            saveConfig();

            p.sendMessage("§aLives updated: §e" + lives);

            LifeGUI.open(p);
        }

        // ---------------- MODE GUI ----------------

        if (title.equals(ModeGUI.TITLE)) {

            String mode;

            switch (e.getSlot()) {

                case 11:
                    mode = "normal";
                    break;

                case 13:
                    mode = "hardcore";
                    break;

                case 15:
                    mode = "chaos";
                    break;

                default:
                    return;
            }

            getConfig().set("player-mode." + p.getUniqueId(), mode);

            saveConfig();

            p.sendMessage("§aMode changed to: §e" + mode);

            p.closeInventory();
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {

        Player p = e.getEntity();

        int lives = getConfig().getInt(
                "lives." + p.getUniqueId(),
                getConfig().getInt("default-lives", 3)
        );

        lives--;

        if (lives < 0) {
            lives = 0;
        }

        getConfig().set("lives." + p.getUniqueId(), lives);

        saveConfig();

        HashMap<String, String> placeholders = new HashMap<>();

        placeholders.put("lives", String.valueOf(lives));

        p.sendMessage(
                WRNAPI.text(
                        p.getUniqueId(),
                        "lives_display",
                        placeholders
                )
        );

        // Out of lives
        if (lives <= 0) {

            p.sendMessage("§cYou are out of lives!");

            // Optional:
            // p.setGameMode(GameMode.SPECTATOR);
            // Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            //         "ban " + p.getName() + " Out of lives");
        }
    }

    // ---------------- UTILS ----------------

    public String getPlayerMode(Player p) {

        return getConfig().getString(
                "player-mode." + p.getUniqueId(),
                "normal"
        );
    }

    public int getLives(Player p) {

        return getConfig().getInt(
                "lives." + p.getUniqueId(),
                getConfig().getInt("default-lives", 3)
        );
    }
}
