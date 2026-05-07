package de.felix.lifeplugin;

import de.felix.lifeplugin.gui.LanguageGUI;
import de.felix.lifeplugin.gui.LifeGUI;
import de.felix.lifeplugin.gui.MarketplaceGUI;
import de.felix.lifeplugin.gui.ModeGUI;
import de.felix.lifeplugin.util.ActionBarUtil;
import de.wrn.api.api.WRNAPI;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener {

    private static Main instance;

    private final HashMap<UUID, UUID> selectedPlayer = new HashMap<>();

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

        // =========================
        // LIVES GUI
        // =========================

        if (cmd.getName().equalsIgnoreCase("livesgui")) {

            if (!p.hasPermission("life.admin")) {
                p.sendMessage("§cNo permission!");
                return true;
            }

            Inventory inv = Bukkit.createInventory(null, 54, LifeGUI.TITLE);

            int slot = 0;

            for (Player target : Bukkit.getOnlinePlayers()) {

                ItemStack head = new ItemStack(Material.PLAYER_HEAD);

                ItemMeta meta = head.getItemMeta();

                meta.setDisplayName("§e" + target.getName());

                head.setItemMeta(meta);

                inv.setItem(slot, head);

                slot++;

                if (slot >= 54) break;
            }

            p.openInventory(inv);

            return true;
        }

        // =========================
        // LANGUAGE GUI
        // =========================

        if (cmd.getName().equalsIgnoreCase("langgui")) {
            LanguageGUI.open(p);
            return true;
        }

        // =========================
        // MODE GUI
        // =========================

        if (cmd.getName().equalsIgnoreCase("modegui")) {
            ModeGUI.open(p);
            return true;
        }

        // =========================
        // MARKETPLACE GUI
        // =========================

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
                || title.equals(MarketplaceGUI.TITLE)
                || title.equals("§cEdit Lives")) {

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
        // PLAYER SELECT GUI
        // =========================

        if (title.equals(LifeGUI.TITLE)) {

            String playerName = item.getItemMeta()
                    .getDisplayName()
                    .replace("§e", "");

            Player target = Bukkit.getPlayer(playerName);

            if (target == null) {
                p.sendMessage("§cPlayer offline!");
                return;
            }

            selectedPlayer.put(
                    p.getUniqueId(),
                    target.getUniqueId()
            );

            Inventory editInv = Bukkit.createInventory(
                    null,
                    27,
                    "§cEdit Lives"
            );

            // +1 Life
            ItemStack add = new ItemStack(Material.LIME_WOOL);

            ItemMeta addMeta = add.getItemMeta();

            addMeta.setDisplayName("§a+1 Life");

            add.setItemMeta(addMeta);

            // -1 Life
            ItemStack remove = new ItemStack(Material.RED_WOOL);

            ItemMeta removeMeta = remove.getItemMeta();

            removeMeta.setDisplayName("§c-1 Life");

            remove.setItemMeta(removeMeta);

            editInv.setItem(11, add);
            editInv.setItem(15, remove);

            p.openInventory(editInv);
        }

        // =========================
        // EDIT LIVES GUI
        // =========================

        if (title.equals("§cEdit Lives")) {

            UUID targetUUID = selectedPlayer.get(p.getUniqueId());

            if (targetUUID == null) {
                p.sendMessage("§cNo player selected!");
                return;
            }

            int lives = getConfig().getInt(
                    "lives." + targetUUID,
                    getConfig().getInt("default-lives", 3)
            );

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

            if (lives < 0) {
                lives = 0;
            }

            if (lives > maxLives) {
                lives = maxLives;
            }

            getConfig().set(
                    "lives." + targetUUID,
                    lives
            );

            saveConfig();

            Player target = Bukkit.getPlayer(targetUUID);

            if (target != null) {
                target.sendMessage(
                        "§eYour lives were updated: §c" + lives
                );
            }

            p.sendMessage("§aLives updated!");
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
