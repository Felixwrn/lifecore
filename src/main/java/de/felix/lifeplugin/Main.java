package de.felix.lifeplugin;

import de.felix.lifeplugin.gui.LifeGUI;
import de.felix.lifeplugin.lang.LanguageManager;
import de.felix.lifeplugin.storage.FileStorage;
import de.felix.lifeplugin.storage.Storage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener {

    private static Main instance;

    private LanguageManager languageManager;
    private final HashMap<UUID, Integer> lives = new HashMap<>();

    private Storage storage; // 🔥 STORAGE

    private String mode = "LIFESTEAL";

    @Override
    public void onEnable() {

        instance = this;

        saveDefaultConfig();

        // 🌍 Language
        languageManager = new LanguageManager();
        languageManager.load(new File(getDataFolder(), "lang"));

        // 💾 Storage aktivieren
        storage = new FileStorage(getDataFolder());

        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("LifePlugin enabled!");
    }

    public static Main getInstance() {
        return instance;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public int getLives(UUID uuid) {
        return lives.getOrDefault(uuid, 10);
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    // 🧍 JOIN → lädt aus Speicher
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {

        Player p = e.getPlayer();

        int saved = storage.getLives(p.getUniqueId());

        lives.put(p.getUniqueId(), saved);

        updateActionBar(p);
    }

    // 💀 DEATH → speichert
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {

        Player p = e.getEntity();

        int current = getLives(p.getUniqueId()) - 1;

        lives.put(p.getUniqueId(), current);

        storage.setLives(p.getUniqueId(), current);
        storage.save(p.getUniqueId());

        if (current <= 0) {

            lives.remove(p.getUniqueId());

            if (mode.equalsIgnoreCase("HARDCORE")) {
                p.kickPlayer(languageManager.get(p.getUniqueId(), "no_lives"));
            } else {
                p.sendMessage(languageManager.get(p.getUniqueId(), "no_lives"));
            }

            return;
        }

        Player killer = p.getKiller();

        // 🧛 Lifesteal
        if (killer != null && mode.equalsIgnoreCase("LIFESTEAL")) {

            int killerLives = getLives(killer.getUniqueId()) + 1;

            lives.put(killer.getUniqueId(), killerLives);

            storage.setLives(killer.getUniqueId(), killerLives);
            storage.save(killer.getUniqueId());

            killer.sendMessage("§a+1 Life");
        }

        getServer().getScheduler().runTaskLater(this, () -> updateActionBar(p), 10L);
    }

    // 📊 ActionBar
    private void updateActionBar(Player p) {

        int l = getLives(p.getUniqueId());

        String msg = languageManager.format(
                p.getUniqueId(),
                "lives",
                "lives", String.valueOf(l)
        );

        p.sendActionBar(msg);
    }

    // 🚫 GUI Protection
    @EventHandler
    public void onInvClick(InventoryClickEvent e) {

        if (e.getView().getTitle().equals(LifeGUI.getTitle())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {

        if (e.getView().getTitle().equals(LifeGUI.getTitle())) {
            e.setCancelled(true);
        }
    }

    // ⌨ Commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) return true;

        // 🌍 Language
        if (cmd.getName().equalsIgnoreCase("language")) {

            if (args.length == 0) {
                p.sendMessage("§cUse: /language <de|en>");
                return true;
            }

            languageManager.setLanguage(p.getUniqueId(), args[0]);
            p.sendMessage("§aLanguage set to " + args[0]);

            return true;
        }

        // ⚙ Mode
        if (cmd.getName().equalsIgnoreCase("mode")) {

            if (!p.isOp()) return true;

            if (args.length == 0) {
                p.sendMessage("§cUse: /mode <hardcore|lifesteal>");
                return true;
            }

            String input = args[0].toLowerCase();

            if (input.equals("hardcore")) {

                mode = "HARDCORE";
                p.sendMessage("§aMode set to HARDCORE");

            } else if (input.equals("lifesteal")) {

                mode = "LIFESTEAL";
                p.sendMessage("§aMode set to LIFESTEAL");

            } else {

                p.sendMessage("§cOnly hardcore or lifesteal allowed!");
            }

            return true;
        }

        // 📦 GUI
        if (cmd.getName().equalsIgnoreCase("livesgui")) {

            LifeGUI.open(p);
            return true;
        }

        return false;
    }

    @Override
    public void onDisable() {
        getLogger().info("LifePlugin disabled!");
    }
}
