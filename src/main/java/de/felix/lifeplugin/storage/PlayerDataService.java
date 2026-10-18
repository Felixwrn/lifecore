package de.felix.lifeplugin.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/** Single-writer async persistence with immutable cache snapshots and retryable dirty data. */
public final class PlayerDataService implements AutoCloseable {
    private final Storage storage;
    private final int defaultLives;
    private final Object lock = new Object();
    private final Map<UUID, PlayerData> cache = new HashMap<>();
    private final Map<UUID, Long> dirty = new HashMap<>();
    private final Map<UUID, CompletableFuture<PlayerData>> loading = new HashMap<>();
    private final ExecutorService executor;
    private long revision;
    private boolean closing;
    private boolean closed;
    private volatile Thread ioThread;

    public PlayerDataService(Storage storage, int defaultLives) {
        this.storage = Objects.requireNonNull(storage);
        if (defaultLives < 0) throw new IllegalArgumentException("Default lives must be nonnegative");
        this.defaultLives = defaultLives;
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "LifeCore-storage");
            thread.setDaemon(true);
            ioThread = thread;
            return thread;
        });
    }

    public CompletableFuture<PlayerData> load(UUID uuid) { return load(uuid, defaultLives); }

    public CompletableFuture<PlayerData> load(UUID uuid, int startingLives) {
        Objects.requireNonNull(uuid);
        if (startingLives < 0) throw new IllegalArgumentException("Starting lives must be nonnegative");
        synchronized (lock) {
            ensureOpen();
            PlayerData cached = cache.get(uuid);
            if (cached != null) return CompletableFuture.completedFuture(cached);
            CompletableFuture<PlayerData> pending = loading.get(uuid);
            if (pending != null) return pending;
            CompletableFuture<PlayerData> result = new CompletableFuture<>();
            loading.put(uuid, result);
            executor.execute(() -> {
                try {
                    var existing = storage.load(uuid);
                    PlayerData data = existing.orElseGet(() -> new PlayerData(startingLives, "en", Map.of(), Set.of()));
                    if (existing.isEmpty()) storage.save(uuid, data);
                    synchronized (lock) { cache.put(uuid, data); loading.remove(uuid); }
                    result.complete(data);
                } catch (Throwable failure) {
                    synchronized (lock) { loading.remove(uuid); }
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }
    }

    public PlayerData get(UUID uuid) {
        synchronized (lock) {
            PlayerData data = cache.get(uuid);
            if (data == null) throw new IllegalStateException("Player data has not been loaded");
            return data;
        }
    }
    public boolean isLoaded(UUID uuid) { synchronized (lock) { return cache.containsKey(uuid); } }

    public PlayerData update(UUID uuid, UnaryOperator<PlayerData> mutation) {
        synchronized (lock) {
            ensureOpen();
            PlayerData current = cache.get(uuid);
            if (current == null) throw new IllegalStateException("Player data has not been loaded");
            PlayerData next = Objects.requireNonNull(mutation.apply(current), "Mutation result");
            if (next.equals(current)) return current;
            cache.put(uuid, next);
            dirty.put(uuid, ++revision);
            // Failed background writes remain dirty and are retried by save/flush/close.
            executor.execute(() -> { try { persist(uuid); } catch (RuntimeException ignored) { /* explicit ack through save/flush */ } });
            return next;
        }
    }

    /** Completes only after all currently pending mutations for this player reached storage. */
    public CompletableFuture<Void> save(UUID uuid) {
        synchronized (lock) {
            ensureOpen();
            if (!cache.containsKey(uuid)) return CompletableFuture.failedFuture(new IllegalStateException("Player data has not been loaded"));
            return CompletableFuture.runAsync(() -> persist(uuid), executor);
        }
    }

    private void persist(UUID uuid) {
        final PlayerData snapshot;
        final Long capturedRevision;
        synchronized (lock) {
            capturedRevision = dirty.get(uuid);
            if (capturedRevision == null) return;
            snapshot = cache.get(uuid);
        }
        storage.save(uuid, snapshot);
        synchronized (lock) { dirty.remove(uuid, capturedRevision); }
    }

    public CompletableFuture<Void> flush() {
        synchronized (lock) {
            ensureOpen();
            return CompletableFuture.runAsync(this::flushDirty, executor);
        }
    }

    private void flushDirty() {
        List<UUID> players;
        synchronized (lock) { players = new ArrayList<>(dirty.keySet()); }
        RuntimeException failure = null;
        for (UUID uuid : players) {
            try { persist(uuid); }
            catch (RuntimeException error) {
                if (failure == null) failure = new StorageException("Cannot flush all player data", error);
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    /** Imports only absent records; current storage and pending cache changes always win. */
    public CompletableFuture<Void> migrateLegacy(Map<UUID, PlayerData> legacy) {
        Map<UUID, PlayerData> snapshot = Map.copyOf(legacy);
        synchronized (lock) {
            ensureOpen();
            return CompletableFuture.runAsync(() -> {
                for (Map.Entry<UUID, PlayerData> entry : snapshot.entrySet()) {
                    synchronized (lock) { if (cache.containsKey(entry.getKey())) continue; }
                    if (storage.load(entry.getKey()).isEmpty()) storage.save(entry.getKey(), entry.getValue());
                }
            }, executor);
        }
    }

    private void ensureOpen() { if (closing || closed) throw new IllegalStateException("Player data service is closed"); }

    @Override public void close() {
        if (Thread.currentThread() == ioThread) throw new IllegalStateException("Cannot close storage from its own I/O thread");
        CompletableFuture<Void> completion;
        synchronized (lock) {
            if (closed) return;
            if (closing) throw new IllegalStateException("Player data service is closing");
            closing = true;
            completion = CompletableFuture.runAsync(this::flushDirty, executor);
            executor.shutdown();
        }
        RuntimeException failure = null;
        try { completion.join(); }
        catch (RuntimeException error) { failure = new StorageException("Cannot flush player data during shutdown", error); }
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (failure == null) failure = new StorageException("Storage executor did not stop");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            if (failure == null) failure = new StorageException("Interrupted while closing storage", error);
        }
        try { storage.close(); }
        catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        synchronized (lock) { closed = true; }
        if (failure != null) throw failure;
    }
}
