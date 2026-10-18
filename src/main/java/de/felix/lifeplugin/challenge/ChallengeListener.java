package de.felix.lifeplugin.challenge;

import de.felix.lifeplugin.Main;
import de.felix.lifeplugin.storage.PlayerDataService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Awards XP on the server thread only after the completed flag has reached durable storage. */
public final class ChallengeListener implements Listener {
    private final ChallengeManager manager;
    private final PlayerDataService data;
    private final BooleanSupplier ready;
    private final Consumer<Runnable> mainThread;
    private final Function<UUID, Player> players;
    private final Consumer<String> warning;

    public ChallengeListener(Main plugin, ChallengeManager manager) {
        this(manager, plugin.getDataService(), plugin::isReady, plugin::mainThread,
                uuid -> plugin.getServer().getPlayer(uuid), message -> plugin.getLogger().warning(message));
    }

    ChallengeListener(ChallengeManager manager, PlayerDataService data, BooleanSupplier ready,
                      Consumer<Runnable> mainThread, Function<UUID, Player> players, Consumer<String> warning) {
        this.manager = manager;
        this.data = data;
        this.ready = ready;
        this.mainThread = mainThread;
        this.players = players;
        this.warning = warning;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!event.isCancelled()) record(event.getPlayer(), "BREAK_BLOCK", event.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) record(killer, "KILL_MOB", event.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.isCancelled() || !(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getCurrentItem();
        if (empty(result) || event.getAction() == InventoryAction.NOTHING
                || event.getAction() == InventoryAction.UNKNOWN) return;
        int amount = result.getAmount();
        if (event.isShiftClick()) {
            int crafts = Integer.MAX_VALUE;
            for (ItemStack ingredient : event.getInventory().getMatrix()) {
                if (!empty(ingredient)) crafts = Math.min(crafts, ingredient.getAmount());
            }
            if (crafts == Integer.MAX_VALUE) return;
            int capacity = 0;
            for (ItemStack slot : player.getInventory().getStorageContents()) {
                if (empty(slot)) capacity += result.getMaxStackSize();
                else if (slot.isSimilar(result)) capacity += Math.max(0, result.getMaxStackSize() - slot.getAmount());
            }
            amount = Math.min(crafts, capacity / result.getAmount()) * result.getAmount();
        }
        if (amount > 0) record(player, "CRAFT_ITEM", result.getType().name(), amount);
    }

    void record(Player player, String event, String target, int amount) {
        if (!ready.getAsBoolean() || data == null || !data.isLoaded(player.getUniqueId())
                || !player.hasPermission("life.challenge")) return;
        UUID uuid = player.getUniqueId();
        List<Challenge> completed = manager.progress(uuid, event, target, amount);
        if (completed.isEmpty()) return;
        data.save(uuid).whenComplete((ignored, failure) -> {
            if (failure != null) {
                warning.accept("Challenge completion could not be saved; XP was withheld. Player: " + uuid);
                return;
            }
            mainThread.accept(() -> {
                if (!ready.getAsBoolean()) return;
                Player online = players.apply(uuid);
                if (online == null || !online.isOnline()) return;
                for (Challenge challenge : completed) {
                    online.giveExp(challenge.getReward());
                    online.sendMessage("§aChallenge completed: §f" + challenge.getName()
                            + " §e(+" + challenge.getReward() + " XP)");
                }
            });
        });
    }

    private static boolean empty(ItemStack stack) {
        if (stack == null || stack.getAmount() == 0) return true;
        Material type = stack.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }
}
