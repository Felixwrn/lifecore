package de.felix.lifeplugin;

import de.felix.lifecore.lang.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener {

    private static Main instance;

    private LanguageManager languageManager;
    private final HashMap<UUID, Integer> lives = new HashMap<>();

    private String mode = "LIFESTEAL";

    @Override
    public void onEnable() {

        instance = this;

        saveDefaultConfig();

        // 🌍 Language System
        languageManager = new LanguageManager();
        languageManager.load(new File(getDataFolder(), "lang"));

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("LifePlugin enabled!");
    }

    public static Main getInstance() {
        return instance;
    }

    // 🔥 FIX → Diese Methode hat gefehlt
    public int getLives(UUID uuid) {
        return lives.getOrDefault(uuid, 10);
    }

    // 🧍 Player Join
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {

        Player p = e.getPlayer();

        lives.putIfAbsent(p.getUniqueId(), 10);

        updateActionBar(p);
    }

    // 💀 Player Death
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {

        Player p = e.getEntity();

        int current = getLives(p.getUniqueId()) - 1;

        lives.put(p.getUniqueId(), current);

        if (current <= 0) {

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

            int steal = 1;

            int killerLives = getLives(killer.getUniqueId());

            lives.put(killer.getUniqueId(), killerLives + steal);

            killer.sendMessage("§a+1 Life");
        }

        Bukkit.getScheduler().runTaskLater(this, () -> updateActionBar(p), 10L);
    }

    // 📊 ActionBar
    private void updateActionBar(Player p) {

        int l = getLives(p.getUniqueId());

        String msg = languageManager.format(
                p.getUniqueId(),
                "lives",
                "lives", String.valueOf(l)
        );

        ActionBarUtil.send(p, msg);
    }

    // 🚫 GUI Protection
    @EventHandler
    public void onInvClick(InventoryClickEvent e) {

        if (e.getView().getTitle().equals("§cLifeCore GUI")) {
            e.setCancelled(true);
        }
    }

    // ⌨️ Commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;

        // 🌍 Language Command
        if (cmd.getName().equalsIgnoreCase("language")) {

            if (args.length == 0) {
                p.sendMessage("§cUse /language <de|en>");
                return true;
            }

            languageManager.setLanguage(p.getUniqueId(), args[0]);

            p.sendMessage("§aLanguage set to " + args[0]);

            return true;
        }

        // ⚙ Mode Command (Admin)
        if (cmd.getName().equalsIgnoreCase("mode")) {

            if (!p.isOp()) return true;

            if (args.length == 0) return true;

            mode = args[0].toUpperCase();

            p.sendMessage("§aMode set to " + mode);

            return true;
        }

        return false;
    }

    @Override
    public void onDisable() {

        getLogger().info("LifePlugin disabled!");
    }
}
