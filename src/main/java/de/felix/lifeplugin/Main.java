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

    private final HashMap<UUID, Integer> lives = new HashMap<>();
    private static Main instance;

    @Override
    public void onEnable() {
        instance = this;
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("LifePlugin gestartet!");
    }

    public static Main getInstance() {
        return instance;
    }

    // 🧍 Join Event
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {

        Player p = event.getPlayer();

        lives.putIfAbsent(p.getUniqueId(), 10);

        updateActionBar(p);
    }

    // 💀 Death Event
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {

        Player p = event.getEntity();

        int current = lives.getOrDefault(p.getUniqueId(), 10);
        current--;

        if (current <= 0) {
            p.kickPlayer("§cDu hast keine Leben mehr!");
            lives.remove(p.getUniqueId());
            return;
        }

        lives.put(p.getUniqueId(), current);

        Bukkit.getScheduler().runTaskLater(this, () -> updateActionBar(p), 10L);
    }

    // 📊 ActionBar
    private void updateActionBar(Player p) {

        int current = lives.getOrDefault(p.getUniqueId(), 10);

        ActionBarUtil.send(p, "§cLeben: §f" + current);
    }

    // ⌨️ Commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) return true;

        if (cmd.getName().equalsIgnoreCase("livesgui")) {

            LifeGUI.open(p);
            return true;
        }

        return false;
    }
}
