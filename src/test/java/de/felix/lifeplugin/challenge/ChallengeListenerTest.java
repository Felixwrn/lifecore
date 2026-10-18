package de.felix.lifeplugin.challenge;

import de.felix.lifeplugin.storage.PlayerData;
import de.felix.lifeplugin.storage.PlayerDataService;
import de.felix.lifeplugin.storage.Storage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class ChallengeListenerTest {
    @TempDir Path temporary;
    private final UUID uuid = UUID.randomUUID();
    private final AtomicInteger awarded = new AtomicInteger();
    private final AtomicBoolean permission = new AtomicBoolean(true);
    private final AtomicBoolean online = new AtomicBoolean(true);
    private final AtomicReference<Thread> awardThread = new AtomicReference<>();
    private PlayerInventory inventory;

    @Test void waitsForDurableAckThenAwardsOnlyThroughMainThreadQueueOnce() throws Exception {
        GateStorage storage = new GateStorage();
        try (PlayerDataService data = new PlayerDataService(storage, 10)) {
            data.load(uuid).join();
            ChallengeManager manager = manager(data, "stone", "BREAK_BLOCK", "STONE", 1);
            Player player = player();
            var scheduled = new LinkedBlockingQueue<Runnable>();
            ChallengeListener listener = new ChallengeListener(manager, data, () -> true, scheduled::add,
                    id -> player, message -> fail(message));
            storage.block.set(true);
            listener.record(player, "BREAK_BLOCK", "STONE", 1);
            assertTrue(storage.entered.await(3, TimeUnit.SECONDS));
            try {
                assertEquals(0, awarded.get());
                assertTrue(scheduled.isEmpty());
                listener.record(player, "BREAK_BLOCK", "STONE", 20);
                assertTrue(scheduled.isEmpty());
            } finally { storage.release.countDown(); }
            Runnable reward = scheduled.poll(3, TimeUnit.SECONDS);
            assertNotNull(reward);
            assertEquals(0, awarded.get());
            assertTrue(storage.saved.get(uuid).completedChallenges().contains("stone"));
            reward.run();
            assertEquals(12, awarded.get());
            assertEquals(Thread.currentThread(), awardThread.get());
            assertTrue(scheduled.isEmpty());
        } finally { storage.release.countDown(); }
    }

    @Test void failedPersistenceWithholdsXpAndReportsFailure() throws Exception {
        GateStorage storage = new GateStorage();
        try (PlayerDataService data = new PlayerDataService(storage, 10)) {
            data.load(uuid).join();
            ChallengeManager manager = manager(data, "stone", "BREAK_BLOCK", "STONE", 1);
            var scheduled = new LinkedBlockingQueue<Runnable>();
            var warnings = new LinkedBlockingQueue<String>();
            Player player = player();
            ChallengeListener listener = new ChallengeListener(manager, data, () -> true, scheduled::add,
                    id -> player, warnings::add);
            storage.fail.set(true);
            try {
                listener.record(player, "BREAK_BLOCK", "STONE", 1);
                assertNotNull(warnings.poll(3, TimeUnit.SECONDS));
                assertEquals(0, awarded.get());
                assertTrue(scheduled.isEmpty());
                assertTrue(data.get(uuid).completedChallenges().contains("stone"));
            } finally { storage.fail.set(false); }
        }
    }

    @Test void respectsPermissionReadinessAndCancelledBlockEvents() throws Exception {
        try (PlayerDataService data = new PlayerDataService(new GateStorage(), 10)) {
            ChallengeManager manager = manager(data, "stone", "BREAK_BLOCK", "STONE", 4);
            Player player = player();
            var scheduled = new LinkedBlockingQueue<Runnable>();
            AtomicBoolean ready = new AtomicBoolean(true);
            ChallengeListener listener = new ChallengeListener(manager, data, ready::get, scheduled::add,
                    id -> player, message -> fail(message));
            listener.record(player, "BREAK_BLOCK", "STONE", 1);
            assertFalse(data.isLoaded(uuid));
            data.load(uuid).join();
            permission.set(false);
            listener.record(player, "BREAK_BLOCK", "STONE", 1);
            permission.set(true);
            ready.set(false);
            listener.record(player, "BREAK_BLOCK", "STONE", 1);
            ready.set(true);
            Block block = proxy(Block.class, (method, args) -> method.equals("getType") ? Material.STONE : null);
            BlockBreakEvent event = new BlockBreakEvent(block, player);
            event.setCancelled(true);
            listener.onBreak(event);
            assertTrue(data.get(uuid).challengeProgress().isEmpty());
            event.setCancelled(false);
            listener.onBreak(event);
            assertEquals(1, data.get(uuid).challengeProgress().get("stone"));
            assertTrue(scheduled.isEmpty());
        }
    }

    @Test void offlinePlayerNeverReceivesBukkitXpCall() throws Exception {
        try (PlayerDataService data = new PlayerDataService(new GateStorage(), 10)) {
            data.load(uuid).join();
            ChallengeManager manager = manager(data, "stone", "BREAK_BLOCK", "STONE", 1);
            Player player = player();
            var scheduled = new LinkedBlockingQueue<Runnable>();
            ChallengeListener listener = new ChallengeListener(manager, data, () -> true, scheduled::add,
                    id -> player, message -> fail(message));
            listener.record(player, "BREAK_BLOCK", "STONE", 1);
            Runnable reward = scheduled.poll(3, TimeUnit.SECONDS);
            assertNotNull(reward);
            online.set(false);
            reward.run();
            assertEquals(0, awarded.get());
        }
    }

    @Test void stoppingPluginDoesNotAwardQueuedExperience() throws Exception {
        try (PlayerDataService data = new PlayerDataService(new GateStorage(), 10)) {
            data.load(uuid).join();
            ChallengeManager manager = manager(data, "stone", "BREAK_BLOCK", "STONE", 1);
            Player player = player();
            var scheduled = new LinkedBlockingQueue<Runnable>();
            AtomicBoolean ready = new AtomicBoolean(true);
            ChallengeListener listener = new ChallengeListener(manager, data, ready::get, scheduled::add,
                    id -> player, message -> fail(message));
            listener.record(player, "BREAK_BLOCK", "STONE", 1);
            Runnable reward = scheduled.poll(3, TimeUnit.SECONDS);
            assertNotNull(reward);
            ready.set(false);
            reward.run();
            assertEquals(0, awarded.get());
        }
    }

    @Test void shiftCraftCountsProducedItemsAndHonorsInventoryCapacity() throws Exception {
        try (PlayerDataService data = new PlayerDataService(new GateStorage(), 10)) {
            data.load(uuid).join();
            ChallengeManager manager = manager(data, "bread", "CRAFT_ITEM", "BREAD", 100);
            AtomicReference<ItemStack[]> contents = new AtomicReference<>(new ItemStack[] {new Stack(Material.BREAD, 60)});
            inventory = proxy(PlayerInventory.class, (method, args) -> method.equals("getStorageContents") ? contents.get() : null);
            Player player = player();
            var scheduled = new LinkedBlockingQueue<Runnable>();
            ChallengeListener listener = new ChallengeListener(manager, data, () -> true, scheduled::add,
                    id -> player, message -> fail(message));
            ItemStack result = new Stack(Material.BREAD, 2);
            CraftingInventory crafting = proxy(CraftingInventory.class, (method, args) ->
                    method.equals("getMatrix") ? new ItemStack[] {new Stack(Material.WHEAT, 10), new Stack(Material.WHEAT, 6)} : null);
            InventoryView view = proxy(InventoryView.class, (method, args) -> switch (method) {
                case "getPlayer" -> player;
                case "getTopInventory", "getInventory" -> crafting;
                case "getItem" -> result;
                case "convertSlot" -> args[0];
                default -> null;
            });
            Recipe recipe = () -> result;
            CraftItemEvent event = new CraftItemEvent(recipe, view, InventoryType.SlotType.RESULT, 0,
                    ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            listener.onCraft(event);
            assertEquals(4, data.get(uuid).challengeProgress().get("bread"));
            contents.set(new ItemStack[] {new Stack(Material.BREAD, 64)});
            listener.onCraft(event);
            assertEquals(4, data.get(uuid).challengeProgress().get("bread"));
            contents.set(new ItemStack[] {null});
            event.setCancelled(true);
            listener.onCraft(event);
            assertEquals(4, data.get(uuid).challengeProgress().get("bread"));
        }
    }

    private ChallengeManager manager(PlayerDataService data, String id, String type, String target, int goal) throws Exception {
        ChallengeManager manager = new ChallengeManager(temporary.resolve("challenges"), data);
        manager.install(new Challenge(id, id, type, target, goal, 12), false);
        return manager;
    }

    private Player player() {
        return proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> uuid;
            case "hasPermission" -> permission.get();
            case "isOnline" -> online.get();
            case "getInventory" -> inventory;
            case "giveExp" -> {
                awarded.addAndGet((Integer) args[0]);
                awardThread.set(Thread.currentThread());
                yield null;
            }
            default -> null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (instance, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(instance);
            if (method.getName().equals("equals")) return instance == args[0];
            if (method.getName().equals("toString")) return "test-" + type.getSimpleName();
            return invocation.apply(method.getName(), args);
        });
    }

    private static final class Stack extends ItemStack {
        Stack(Material type, int amount) { super(type, amount); }
        @Override public int getMaxStackSize() { return 64; }
        @Override public boolean isSimilar(ItemStack other) { return other != null && getType() == other.getType(); }
    }

    private static final class GateStorage implements Storage {
        final Map<UUID, PlayerData> saved = new ConcurrentHashMap<>();
        final AtomicBoolean block = new AtomicBoolean();
        final AtomicBoolean fail = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public Optional<PlayerData> load(UUID player) { return Optional.ofNullable(saved.get(player)); }
        @Override public void save(UUID player, PlayerData snapshot) {
            if (!snapshot.completedChallenges().isEmpty()) {
                if (fail.get()) throw new IllegalStateException("simulated unavailable storage");
                if (block.get()) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test storage timed out");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
            }
            saved.put(player, snapshot);
        }
        @Override public void close() {}
    }
}
