package de.felix.lifeplugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener {

    private static Main instance;

    private Storage storage;

    private GameModeType mode;

    @Override
    public void onEnable() {

        instance = this;

        saveDefaultConfig();

        // 🔧 Mode laden
        String m = getConfig().getString("mode", "LIFESTEAL");
        mode = GameModeType.valueOf(m.toUpperCase());

        // 🗄️ Storage laden
        String type = getConfig().getString("storage.type", "FILE");

        if (type.equalsIgnoreCase("MYSQL")) {

            MySQL mysql = new MySQL();

            storage = new MySQLStorage(
                    mysql.connect(
                            getConfig().getString("mysql.host"),
                            getConfig().getString("mysql.database"),
                            getConfig().getString("mysql.user"),
                            getConfig().getString("mysql.password")
                    )
            );

        } else {
            storage = new FileStorage(this);
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("LifePlugin gestartet | Mode: " + mode);
    }

    public static Main getInstance() {
        return instance;
    }

    public GameModeType getMode() {
        return mode;
    }

    public int getLives(UUID uuid) {
        return storage.getLives(uuid);
    }

    // 🧍 Join
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {

        Player p = e.getPlayer();

        storage.loadPlayer(p.getUniqueId());

        updateActionBar(p);
    }

    // 💀 Death System
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {

        Player p = e.getEntity();

        // 🧛 Lifesteal
        if (mode == GameModeType.LIFESTEAL) {
            handleLifeSteal(p, e.getEntity().getKiller());
        }

        int lives = storage.getLives(p.getUniqueId()) - 1;

        storage.savePlayer(p.getUniqueId(), lives);

        if (lives <= 0) {

            if (mode == GameModeType.HARDCORE) {
                p.kickPlayer("§cHardcore: keine Leben mehr!");
            } else {
                p.sendMessage("§cDu hast keine Leben mehr!");
            }

            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> updateActionBar(p), 10L);
    }

    // 🧛 Lifesteal
    private void handleLifeSteal(Player dead, Player killer) {

        if (killer == null) return;

        int steal = getConfig().getInt("lifesteal.steal-amount", 1);
        int max = getConfig().getInt("lifesteal.max-lives", 20);

        int current = storage.getLives(killer.getUniqueId());

        if (current >= max) return;

        current += steal;

        storage.savePlayer(killer.getUniqueId(), current);

        killer.sendMessage("§a+1 Leben durch Kill!");
    }

    // 📊 ActionBar
    private void updateActionBar(Player p) {

        int lives = storage.getLives(p.getUniqueId());

        ActionBarUtil.send(p, "§cLeben: §f" + lives + " §7| Mode: " + mode);
    }

    // ⌨️ Commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) return true;

        // GUI öffnen
        if (cmd.getName().equalsIgnoreCase("livesgui")) {

            LifeGUI.open(p);
            return true;
        }

        // Mode ändern
        if (cmd.getName().equalsIgnoreCase("mode")) {

            if (!p.isOp()) return true;

            if (args.length == 0) return true;

            mode = GameModeType.valueOf(args[0].toUpperCase());

            getConfig().set("mode", mode.toString());
            saveConfig();

            p.sendMessage("§aMode gesetzt: " + mode);

            return true;
        }

        return false;
    }

    @Override
    public void onDisable() {

        for (Player p : Bukkit.getOnlinePlayers()) {
            storage.savePlayer(p.getUniqueId(), storage.getLives(p.getUniqueId()));
        }
    }
}
