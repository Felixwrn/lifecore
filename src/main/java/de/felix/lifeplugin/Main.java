package de.felix.lifeplugin;

import de.felix.lifeplugin.gui.LifeGUI;
import de.felix.lifeplugin.lang.LanguageManager;
import de.felix.lifeplugin.storage.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class Main extends JavaPlugin implements Listener {

    private static Main instance;

    private LanguageManager languageManager;
    private Storage storage;

    private final HashMap<UUID, Integer> lives = new HashMap<>();

    private String mode;

    private File langConfigFile;
    private YamlConfiguration langConfig;

    @Override
    public void onEnable() {

        instance = this;

        saveDefaultConfig();
        loadLangConfig();

        languageManager = new LanguageManager();
        languageManager.load(new File(getDataFolder(), "lang"));

        mode = getConfig().getString("mode", "LIFESTEAL");

        // Storage
        String type = getConfig().getString("storage.type");

        if ("MYSQL".equalsIgnoreCase(type)) {
            storage = new MySQLStorage(
                    getConfig().getString("mysql.host"),
                    getConfig().getInt("mysql.port"),
                    getConfig().getString("mysql.database"),
                    getConfig().getString("mysql.user"),
                    getConfig().getString("mysql.password")
            );
        } else {
            storage = new FileStorage(getDataFolder());
        }

        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("LifePlugin enabled!");
    }

    private void loadLangConfig() {
        langConfigFile = new File(getDataFolder(), "languages.yml");

        if (!langConfigFile.exists()) {
            saveResource("languages.yml", false);
        }

        langConfig = YamlConfiguration.loadConfiguration(langConfigFile);
    }

    public static Main getInstance() {
        return instance;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public int getLives(UUID uuid) {
        return lives.getOrDefault(uuid, getConfig().getInt("start-lives", 10));
    }

    // JOIN
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        int loaded = storage.getLives(p.getUniqueId());
        lives.put(p.getUniqueId(), loaded);

        updateActionBar(p);
    }

    // DEATH
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {

        Player p = e.getEntity();

        int current = getLives(p.getUniqueId()) - 1;

        lives.put(p.getUniqueId(), current);
        storage.setLives(p.getUniqueId(), current);
        storage.save(p.getUniqueId());

        if (current <= 0) {
            p.kickPlayer(languageManager.get(p.getUniqueId(), "no_lives"));
            return;
        }

        Player killer = p.getKiller();

        if (killer != null && mode.equalsIgnoreCase("LIFESTEAL")) {

            int steal = getConfig().getInt("lifesteal.steal-amount", 1);
            int max = getConfig().getInt("lifesteal.max-lives", 20);

            int newLives = Math.min(getLives(killer.getUniqueId()) + steal, max);

            lives.put(killer.getUniqueId(), newLives);
            storage.setLives(killer.getUniqueId(), newLives);
            storage.save(killer.getUniqueId());
        }
    }

    private void updateActionBar(Player p) {
        String msg = languageManager.format(
                p.getUniqueId(),
                "lives",
                "lives", String.valueOf(getLives(p.getUniqueId()))
        );
        p.sendActionBar(msg);
    }

    // GUI BLOCK
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTitle().equals(LifeGUI.getTitle())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTitle().equals(LifeGUI.getTitle())) e.setCancelled(true);
    }

    // COMMANDS
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) return true;

        // LANGUAGE DOWNLOAD
        if (cmd.getName().equalsIgnoreCase("language")) {

            if (args.length == 2 && args[0].equalsIgnoreCase("download")) {
                downloadLanguage(args[1], p);
                return true;
            }

            if (args.length == 1) {
                languageManager.setLanguage(p.getUniqueId(), args[0]);
                p.sendMessage("§aLanguage set to " + args[0]);
                return true;
            }
        }

        // RELOAD
        if (cmd.getName().equalsIgnoreCase("lifecore")) {

            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {

                reloadConfig();
                loadLangConfig();

                languageManager.load(new File(getDataFolder(), "lang"));

                mode = getConfig().getString("mode", "LIFESTEAL");

                for (Player online : getServer().getOnlinePlayers()) {
                    int loaded = storage.getLives(online.getUniqueId());
                    lives.put(online.getUniqueId(), loaded);
                    updateActionBar(online);
                }

                p.sendMessage("§aReloaded!");
                return true;
            }
        }

        // GUI
        if (cmd.getName().equalsIgnoreCase("livesgui")) {
            LifeGUI.open(p);
            return true;
        }

        return false;
    }

    // DOWNLOAD
    private void downloadLanguage(String lang, Player p) {

        getServer().getScheduler().runTaskAsynchronously(this, () -> {

            try {
                String urlStr = langConfig.getString("languages." + lang);

                if (urlStr == null) {
                    p.sendMessage("§cLanguage not found!");
                    return;
                }

                URL url = new URL(urlStr);

                File folder = new File(getDataFolder(), "lang");
                if (!folder.exists()) folder.mkdirs();

                File file = new File(folder, lang + ".json");

                Files.copy(url.openStream(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);

                p.sendMessage("§aDownloaded " + lang);

                languageManager.load(folder);

            } catch (Exception e) {
                p.sendMessage("§cDownload failed!");
                e.printStackTrace();
            }
        });
    }

    @Override
    public void onDisable() {

        for (UUID uuid : lives.keySet()) {
            storage.setLives(uuid, lives.get(uuid));
            storage.save(uuid);
        }

        getLogger().info("LifePlugin disabled!");
    }
}
