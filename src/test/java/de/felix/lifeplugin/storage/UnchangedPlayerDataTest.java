package de.felix.lifeplugin.storage;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UnchangedPlayerDataTest {
    @Test void unrelatedEventsDoNotScheduleDatabaseWrites() {
        AtomicInteger writes = new AtomicInteger();
        Storage storage = new Storage() {
            public Optional<PlayerData> load(UUID id) { return Optional.empty(); }
            public void save(UUID id, PlayerData value) { writes.incrementAndGet(); }
            public void close() { }
        };
        UUID id = UUID.randomUUID();
        try (PlayerDataService service = new PlayerDataService(storage, 3)) {
            service.load(id).join();
            for (int i = 0; i < 100; i++) service.update(id, p -> p.withLives(p.lives()));
            service.flush().join();
            assertEquals(1, writes.get(), "Only the initial player record is written");
            service.update(id, p -> p.withLives(2));
            service.save(id).join();
            assertEquals(2, writes.get());
        }
    }
}
