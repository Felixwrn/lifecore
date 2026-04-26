package de.felix.lifeplugin;

import de.felix.lifeplugin.gui.LanguageGUI;
import de.felix.lifeplugin.gui.LifeGUI;
import de.felix.lifeplugin.gui.ModeGUI;
import de.felix.lifeplugin.lang.LanguageManager;
import de.felix.lifeplugin.storage.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
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

public class Main extends JavaPlugin implements Listener, TabExecutor {

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
        copyDefaultLanguages();

        languageManager = new LanguageManager();
        languageManager.load(new File(getDataFolder(), "lang"));

        mode = getConfig().getString("mode", "LIFESTEAL");

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

        getCommand("mode").setExecutor(this);
        getCommand("mode").setTabCompleter(this);

        getLogger().info("LifePlugin enabled!");
    }

    private void loadLangConfig() {
        langConfigFile = new File(getDataFolder(), "languages.yml");

        if (!langConfigFile.exists()) {
            saveResource("languages.yml", false);
        }

        langConfig = YamlConfiguration.loadConfiguration(langConfigFile);
    }

    private void copyDefaultLanguages() {
        File langFolder = new File(getDataFolder(), "lang");

        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        String[] defaults = {"de.json", "en.json"};

        for (String file : defaults) {
            File target = new File(langFolder, file);
            if (!target.exists()) {
                saveResource("lang/" + file, false);
            }
        }
    }

    public static Main getInstance() {
        return instance;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public YamlConfiguration getLangConfig() {
        return langConfig;
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

    // GUI EVENTS
    @EventHandler
    public void onClick(InventoryClickEvent e) {

        if (!(e.getWhoClicked() instanceof Player p)) return;

        // LifeGUI
        if (e.getView().getTitle().equals(LifeGUI.getTitle(p))) {
            e.setCancelled(true);
            return;
        }

        // LanguageGUI
        if (e.getView().getTitle().equals(LanguageGUI.getTitle(p))) {

            e.setCancelled(true);

            if (e.getCurrentItem() == null || e.getCurrentItem().getItemMeta() == null) return;

            String name = e.getCurrentItem().getItemMeta().getDisplayName();

            if (name.contains("Next") || name.contains("Weiter")) {
                LanguageGUI.open(p, LanguageGUI.getPage(p.getUniqueId()) + 1);
                return;
            }

            if (name.contains("Previous") || name.contains("Zurück")) {
                LanguageGUI.open(p, LanguageGUI.getPage(p.getUniqueId()) - 1);
                return;
            }

            String lang = name.replace("§6★ ", "").replace("§f§l", "").toLowerCase();

            File file = new File(getDataFolder(), "lang/" + lang + ".json");

            if (file.exists()) {
                languageManager.setLanguage(p.getUniqueId(), lang);
                p.sendMessage("§aLanguage set to " + lang);
            } else {
                downloadLanguage(lang, p);
            }

            p.closeInventory();
        }

        // ModeGUI
        if (e.getView().getTitle().equals(ModeGUI.getTitle(p))) {

            e.setCancelled(true);

            if (e.getCurrentItem() == null) return;

            Material mat = e.getCurrentItem().getType();

            String newMode;

            if (mat == Material.REDSTONE_BLOCK) {
                newMode = "HARDCORE";
            } else if (mat == Material.HEART_OF_THE_SEA) {
                newMode = "LIFESTEAL";
            } else {
                return;
            }

            mode = newMode;

            getConfig().set("mode", mode);
            saveConfig();

            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);

            Bukkit.getScheduler().runTaskLater(this, () -> ModeGUI.open(p), 5L);

            p.sendMessage("§aMode set to " + mode);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {

        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getView().getTitle().equals(LifeGUI.getTitle(p))) e.setCancelled(true);
        if (e.getView().getTitle().equals(LanguageGUI.getTitle(p))) e.setCancelled(true);
        if (e.getView().getTitle().equals(ModeGUI.getTitle(p))) e.setCancelled(true);
    }

    // COMMANDS
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) return true;

        // ADMIN LIFE COMMAND
        if (cmd.getName().equalsIgnoreCase("life")) {

            if (!p.hasPermission("lifecore.admin")) {
                p.sendMessage("§cNo permission!");
                return true;
            }

            if (args.length < 2) {
                p.sendMessage("§cUsage: /life <give/set/remove/reset> <player> [amount]");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);

            if (target == null) {
                p.sendMessage("§cPlayer not found!");
                return true;
            }

            UUID uuid = target.getUniqueId();
            int current = getLives(uuid);

            int amount = 0;
            if (args.length >= 3) {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (Exception e) {
                    p.sendMessage("§cInvalid number!");
                    return true;
                }
            }

            switch (args[0].toLowerCase()) {

                case "give" -> {
                    int newLives = current + amount;
                    lives.put(uuid, newLives);
                    storage.setLives(uuid, newLives);
                    storage.save(uuid);
                    p.sendMessage("§aGave " + amount + " lives to " + target.getName());
                }

                case "set" -> {
                    lives.put(uuid, amount);
                    storage.setLives(uuid, amount);
                    storage.save(uuid);
                    p.sendMessage("§aSet lives of " + target.getName() + " to " + amount);
                }

                case "remove" -> {
                    int newLives = Math.max(0, current - amount);
                    lives.put(uuid, newLives);
                    storage.setLives(uuid, newLives);
                    storage.save(uuid);
                    p.sendMessage("§cRemoved " + amount + " lives from " + target.getName());
                }

                case "reset" -> {
                    int start = getConfig().getInt("start-lives", 10);
                    lives.put(uuid, start);
                    storage.setLives(uuid, start);
                    storage.save(uuid);
                    p.sendMessage("§eReset lives of " + target.getName());
                }

                default -> p.sendMessage("§cUnknown subcommand!");
            }

            return true;
        }

        // MODE COMMAND
        if (cmd.getName().equalsIgnoreCase("mode")) {
            ModeGUI.open(p);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("livesgui")) {
            LifeGUI.open(p);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("langgui")) {
            LanguageGUI.open(p);
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
