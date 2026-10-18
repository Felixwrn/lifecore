package de.felix.lifeplugin.storage;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDataServiceTest {
    static final class MemoryStorage implements Storage {
        final Map<UUID, PlayerData> values = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicBoolean fail = new AtomicBoolean();
        volatile boolean closed;
        volatile CountDownLatch entered, release;
        volatile Thread ioThread;
        @Override public Optional<PlayerData> load(UUID uuid) {
            ioThread = Thread.currentThread();
            reads.incrementAndGet();
            if (fail.get()) throw new StorageException("intentional read failure");
            return Optional.ofNullable(values.get(uuid));
        }
        @Override public void save(UUID uuid, PlayerData data) {
            ioThread = Thread.currentThread();
            CountDownLatch notify = entered, waitFor = release;
            if (notify != null && waitFor != null) {
                entered = null;
                notify.countDown();
                try { if (!waitFor.await(5, TimeUnit.SECONDS)) throw new StorageException("test write timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new StorageException("interrupted", e); }
            }
            if (fail.get()) throw new StorageException("intentional write failure");
            values.put(uuid, data);
        }
        @Override public void close() { closed = true; }
    }
    static PlayerData data(int lives) { return new PlayerData(lives, "en", Map.of(), Set.of()); }

    @Test void loadsOnceUsesDynamicDefaultsAndRequiresLoadedPlayers() {
        MemoryStorage storage = new MemoryStorage();
        UUID uuid = UUID.randomUUID();
        try (PlayerDataService service = new PlayerDataService(storage, 3)) {
            assertThrows(IllegalStateException.class, () -> service.get(uuid));
            assertThrows(IllegalStateException.class, () -> service.update(uuid, p -> p.withLives(1)));
            CompletableFuture<PlayerData> first = service.load(uuid, 8);
            CompletableFuture<PlayerData> second = service.load(uuid, 2);
            assertEquals(8, first.join().lives());
            assertEquals(8, second.join().lives());
            assertTrue(service.isLoaded(uuid));
            assertEquals(1, storage.reads.get());
            assertNotEquals(Thread.currentThread(), storage.ioThread);
            assertEquals(8, storage.values.get(uuid).lives());
        }
        assertTrue(storage.closed);
    }

    @Test void failedReadDoesNotCreateOrCacheDefaultAndCanBeRetried() {
        MemoryStorage storage = new MemoryStorage();
        UUID uuid = UUID.randomUUID();
        storage.fail.set(true);
        try (PlayerDataService service = new PlayerDataService(storage, 3)) {
            assertThrows(java.util.concurrent.CompletionException.class, () -> service.load(uuid).join());
            assertFalse(service.isLoaded(uuid));
            assertFalse(storage.values.containsKey(uuid));
            storage.fail.set(false);
            assertEquals(3, service.load(uuid).join().lives());
        }
    }

    @Test void failedWritesRemainDirtyUntilAcknowledgedRetryAndCloseFlushes() {
        MemoryStorage storage = new MemoryStorage();
        UUID uuid = UUID.randomUUID();
        PlayerDataService service = new PlayerDataService(storage, 3);
        service.load(uuid).join();
        storage.fail.set(true);
        service.update(uuid, p -> p.withLives(1).withLanguage("de").withProgress("stone", 4).completeChallenge("stone"));
        assertThrows(java.util.concurrent.CompletionException.class, () -> service.save(uuid).join());
        assertThrows(java.util.concurrent.CompletionException.class, () -> service.flush().join());
        assertEquals(3, storage.values.get(uuid).lives());
        assertEquals(1, service.get(uuid).lives());
        storage.fail.set(false);
        service.save(uuid).join();
        assertEquals(service.get(uuid), storage.values.get(uuid));
        service.update(uuid, p -> p.withLives(5));
        service.close();
        assertEquals(5, storage.values.get(uuid).lives());
        assertTrue(storage.closed);
        assertThrows(IllegalStateException.class, () -> service.load(uuid));
    }

    @Test void overlappingMutationCannotBeClearedByAnOlderWrite() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        UUID uuid = UUID.randomUUID();
        try (PlayerDataService service = new PlayerDataService(storage, 3)) {
            service.load(uuid).join();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            storage.entered = entered;
            storage.release = release;
            service.update(uuid, p -> p.withLives(4));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.update(uuid, p -> p.withLives(9));
            release.countDown();
            service.flush().join();
            assertEquals(9, storage.values.get(uuid).lives());
        }
    }

    @Test void migrationPreservesExistingPersistentAndCachedRecords() {
        MemoryStorage storage = new MemoryStorage();
        UUID stored = UUID.randomUUID(), missing = UUID.randomUUID(), cached = UUID.randomUUID();
        storage.values.put(stored, data(7));
        try (PlayerDataService service = new PlayerDataService(storage, 3)) {
            service.load(cached).join();
            service.update(cached, p -> p.withLives(9));
            service.migrateLegacy(Map.of(stored, data(1), missing, data(2), cached, data(1))).join();
            service.flush().join();
            assertEquals(7, storage.values.get(stored).lives());
            assertEquals(2, storage.values.get(missing).lives());
            assertEquals(9, storage.values.get(cached).lives());
        }
    }
}
