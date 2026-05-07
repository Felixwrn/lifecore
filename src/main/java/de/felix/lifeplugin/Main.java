package de.felix.lifeplugin;

import de.felix.lifeplugin.gui.LanguageGUI;
import de.felix.lifeplugin.gui.LifeGUI;
import de.felix.lifeplugin.gui.MarketplaceGUI;
import de.felix.lifeplugin.gui.ModeGUI;
import de.felix.lifeplugin.util.ActionBarUtil;
import de.wrn.api.api.WRNAPI;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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

        boolean actionbarEnabled = getConfig().getBoolean(
                "settings.actionbar",
                true
        );

        int interval = getConfig().getInt(
                "settings.update-interval",
                40
        );

        // ActionBar Loop
        if (actionbarEnabled) {

            Bukkit.getScheduler().runTaskTimer(this, () -> {

                for (Player p : Bukkit.getOnlinePlayers()) {

                    int lives = getLives(p);

                    String mode = getServerMode();

                    ActionBarUtil.send(
                            p,
                            "§cLives: §f" + lives +
                                    " §7| §bMode: §f" + mode
                    );
                }

            }, 0L, interval);
        }

        getLogger().info("§aWRN LifePlugin enabled!");
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

        // Marketplace GUI
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

        // GUI Protection
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

        // =========================
        // LANGUAGE GUI
        // =========================

        if (title.equals(LanguageGUI.TITLE)) {

            String name = item.getItemMeta().getDisplayName();

            String lang = name.contains("(en)") ? "en" : "de";

            WRNAPI.setLanguage(p.getUniqueId(), lang);

            p.sendMessage("§aLanguage changed to §e" + lang);

            p.closeInventory();
        }

        // =========================
        // LIFE GUI
        // =========================

        if (title.equals(LifeGUI.TITLE)) {

            int lives = getLives(p);

            // +1
            if (e.getSlot() == 11) {
                lives++;
            }

            // -1
            if (e.getSlot() == 15) {
                lives--;
            }

            int maxLives = getConfig().getInt(
                    "max-lives",
                    10
            );

            // Limits
            if (lives < 0) {
                lives = 0;
            }

            if (lives > maxLives) {
                lives = maxLives;
            }

            getConfig().set(
                    "lives." + p.getUniqueId(),
                    lives
            );

            saveConfig();

            p.sendMessage("§aLives updated: §e" + lives);

            LifeGUI.open(p);
        }

        // =========================
        // MODE GUI
        // =========================

        if (title.equals(ModeGUI.TITLE)) {

            String mode;

            switch (e.getSlot()) {

                case 11:
                    mode = "hardcore";
                    break;

                case 13:
                    mode = "pro";
                    break;

                case 15:
                    mode = "vanilla_plus";
                    break;

                default:
                    return;
            }

            // SAVE GLOBAL SERVER MODE
            getConfig().set("mode.current", mode);

            saveConfig();

            p.sendMessage("§aServer mode changed to: §e" + mode);

            p.closeInventory();
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {

        Player p = e.getEntity();

        int lives = getLives(p);

        lives--;

        if (lives < 0) {
            lives = 0;
        }

        getConfig().set(
                "lives." + p.getUniqueId(),
                lives
        );

        saveConfig();

        HashMap<String, String> placeholders = new HashMap<>();

        placeholders.put(
                "lives",
                String.valueOf(lives)
        );

        p.sendMessage(
                WRNAPI.text(
                        p.getUniqueId(),
                        "lives_display",
                        placeholders
                )
        );

        // CURRENT MODE
        String mode = getServerMode();

        boolean banOnZero = getConfig().getBoolean(
                "marketplace." + mode + ".banOnZero",
                true
        );

        // OUT OF LIVES
        if (lives <= 0) {

            p.sendMessage("§cYou are out of lives!");

            if (banOnZero) {

                p.setGameMode(GameMode.SPECTATOR);

                p.sendMessage("§cYou are now in spectator mode!");
            }
        }
    }

    // =========================
    // UTIL METHODS
    // =========================

    public String getServerMode() {

        return getConfig().getString(
                "mode.current",
                "hardcore"
        );
    }

    public int getLives(Player p) {

        return getConfig().getInt(
                "lives." + p.getUniqueId(),
                getConfig().getInt("default-lives", 3)
        );
    }
}
