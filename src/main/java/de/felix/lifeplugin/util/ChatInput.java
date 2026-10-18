package de.felix.lifeplugin.util;

import de.felix.lifeplugin.Main;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

/** Private, single-use chat requests; asynchronous chat never invokes a Bukkit callback. */
public final class ChatInput implements Listener {
    private static volatile ChatInput instance;
    private final Main plugin;
    private final PendingInputs pending = new PendingInputs();

    public ChatInput(Main plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        ChatInput previous = instance;
        if (previous != null) previous.clear();
        instance = this;
    }

    /** Kept for existing menu integrations. Register ChatInput(Main) before using it. */
    public static void wait(Player player, Consumer<String> callback) {
        ChatInput input = instance;
        if (input == null || !input.plugin.isEnabled()) {
            throw new IllegalStateException("Chat input is not available");
        }
        Objects.requireNonNull(callback);
        if (Bukkit.isPrimaryThread()) input.begin(player, callback);
        else Bukkit.getScheduler().runTask(input.plugin, () -> input.begin(player, callback));
    }

    private void begin(Player player, Consumer<String> callback) {
        if (!plugin.isEnabled() || instance != this || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        Pending request = new Pending(callback);
        Pending previous = pending.replace(uuid, request);
        if (previous != null) previous.cancelTimer();
        request.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(uuid, request)) {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) online.sendMessage("§eChat input expired.");
            }
        }, 20L * 60);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void chat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Pending request = pending.get(uuid);
        if (request == null) return;
        event.setCancelled(true);
        if (!request.claim()) return;
        String message = event.getMessage();
        // Keep the request registered until delivery, so quit, timeout or replacement wins safely.
        if (!plugin.isEnabled()) { pending.remove(uuid, request); return; }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> deliver(uuid, request, message));
        } catch (IllegalStateException | IllegalPluginAccessException stopped) {
            if (pending.remove(uuid, request)) request.cancelTimer();
        }
    }

    private void deliver(UUID uuid, Pending request, String message) {
        if (!pending.remove(uuid, request)) return;
        request.cancelTimer();
        if (!plugin.isEnabled() || instance != this) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;
        if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("/cancel")) {
            player.sendMessage("§eChat input cancelled.");
            return;
        }
        try { request.callback.accept(message); }
        catch (RuntimeException failure) {
            plugin.getLogger().warning("Chat input callback failed: " + failure.getClass().getSimpleName());
            player.sendMessage("§cThe input could not be applied. Reopen the menu and try again.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void command(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().equalsIgnoreCase("/cancel")) return;
        Pending request = pending.remove(event.getPlayer().getUniqueId());
        if (request == null) return;
        event.setCancelled(true);
        request.cancelTimer();
        event.getPlayer().sendMessage("§eChat input cancelled.");
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        Pending request = pending.remove(event.getPlayer().getUniqueId());
        if (request != null) request.cancelTimer();
    }

    public static void shutdown() {
        ChatInput input = instance;
        instance = null;
        if (input != null) input.clear();
    }

    private void clear() { pending.clear(); }

    /** Identity-aware removals stop an older chat task consuming a newer prompt. */
    static final class PendingInputs {
        private final ConcurrentHashMap<UUID, Pending> inputs = new ConcurrentHashMap<>();
        Pending replace(UUID uuid, Pending request) { return inputs.put(uuid, request); }
        Pending get(UUID uuid) { return inputs.get(uuid); }
        Pending remove(UUID uuid) { return inputs.remove(uuid); }
        boolean remove(UUID uuid, Pending request) { return inputs.remove(uuid, request); }
        void clear() { inputs.values().forEach(Pending::cancelTimer); inputs.clear(); }
    }

    static final class Pending {
        private final Consumer<String> callback;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private volatile BukkitTask timeout;
        Pending(Consumer<String> callback) { this.callback = callback; }
        boolean claim() { return claimed.compareAndSet(false, true); }
        void cancelTimer() { BukkitTask timer = timeout; if (timer != null) timer.cancel(); }
    }
}
