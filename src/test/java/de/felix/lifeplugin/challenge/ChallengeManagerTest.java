package de.felix.lifeplugin.challenge;

import de.felix.lifeplugin.storage.FileStorage;
import de.felix.lifeplugin.storage.PlayerDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ChallengeManagerTest {
    @TempDir Path temporary;
    private final UUID player = UUID.randomUUID();

    @Test void capsProgressAndCompletesOnlyOnceAcrossRestart() throws Exception {
        Path playerFolder = temporary.resolve("players");
        Path challengeFolder = temporary.resolve("challenges");
        try (PlayerDataService data = new PlayerDataService(new FileStorage(playerFolder), 10)) {
            data.load(player).join();
            ChallengeManager manager = new ChallengeManager(challengeFolder, data);
            manager.install(new Challenge("stone", "Stone", "BREAK_BLOCK", "STONE", 3, 12), false);
            assertTrue(manager.progress(player, "BREAK_BLOCK", "DIRT", 20).isEmpty());
            assertTrue(manager.progress(player, "KILL_MOB", "STONE", 20).isEmpty());
            assertTrue(manager.progress(player, "BREAK_BLOCK", "STONE", 2).isEmpty());
            assertEquals(2, data.get(player).challengeProgress().get("stone"));
            List<Challenge> newly = manager.progress(player, "BREAK_BLOCK", "STONE", 20);
            assertEquals(1, newly.size());
            assertEquals(3, data.get(player).challengeProgress().get("stone"));
            assertTrue(data.get(player).completedChallenges().contains("stone"));
            assertTrue(manager.progress(player, "BREAK_BLOCK", "STONE", 20).isEmpty());
            data.save(player).join();
        }
        try (PlayerDataService data = new PlayerDataService(new FileStorage(playerFolder), 10)) {
            data.load(player).join();
            ChallengeManager manager = new ChallengeManager(challengeFolder, data);
            manager.loadChallenges();
            assertEquals(3, data.get(player).challengeProgress().get("stone"));
            assertTrue(manager.progress(player, "BREAK_BLOCK", "STONE", 20).isEmpty());
        }
    }

    @Test void resumesIncompleteProgressAfterRestartAndKeepsPlayersSeparate() throws Exception {
        try (PlayerDataService data = new PlayerDataService(new FileStorage(temporary.resolve("players")), 10)) {
            data.load(player).join();
            UUID other = UUID.randomUUID();
            data.load(other).join();
            ChallengeManager manager = new ChallengeManager(temporary.resolve("challenges"), data);
            manager.install(new Challenge("bread", "Bread", "CRAFT_ITEM", "BREAD", 4, 12), false);
            assertTrue(manager.progress(player, "CRAFT_ITEM", "BREAD", 2).isEmpty());
            assertEquals(1, manager.progress(other, "CRAFT_ITEM", "BREAD", 4).size());
        }
        try (PlayerDataService data = new PlayerDataService(new FileStorage(temporary.resolve("players")), 10)) {
            data.load(player).join();
            ChallengeManager manager = new ChallengeManager(temporary.resolve("challenges"), data);
            manager.loadChallenges();
            assertEquals(1, manager.progress(player, "CRAFT_ITEM", "BREAD", 2).size());
        }
    }

    @Test void concurrentEventsCannotCompleteTwice() throws Exception {
        try (PlayerDataService data = new PlayerDataService(new FileStorage(temporary.resolve("players")), 10);
             var threads = Executors.newFixedThreadPool(4)) {
            data.load(player).join();
            ChallengeManager manager = new ChallengeManager(temporary.resolve("challenges"), data);
            manager.install(new Challenge("zombie", "Zombie", "KILL_MOB", "ZOMBIE", 3, 12), false);
            List<Callable<Integer>> calls = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(index -> (Callable<Integer>) () -> manager.progress(player, "KILL_MOB", "ZOMBIE", 1).size()).toList();
            int completions = 0;
            for (var result : threads.invokeAll(calls)) completions += result.get();
            assertEquals(1, completions);
            assertEquals(3, data.get(player).challengeProgress().get("zombie"));
        }
    }

    @Test void refusesUnloadedPlayersAndBadIncrements() throws Exception {
        try (PlayerDataService data = new PlayerDataService(new FileStorage(temporary.resolve("players")), 10)) {
            ChallengeManager manager = new ChallengeManager(temporary.resolve("challenges"), data);
            assertTrue(manager.progress(player, "BREAK_BLOCK", "STONE", 1).isEmpty());
            assertFalse(data.isLoaded(player));
            assertThrows(IllegalArgumentException.class, () -> manager.progress(player, "BREAK_BLOCK", "STONE", 0));
            assertThrows(IllegalArgumentException.class, () -> manager.progress(player, "BREAK_BLOCK", "STONE", Integer.MAX_VALUE));
        }
    }

    @Test void requiresExplicitOverwriteAndRetainsCacheWhenReloadFails() throws Exception {
        try (PlayerDataService data = new PlayerDataService(new FileStorage(temporary.resolve("players")), 10)) {
            ChallengeManager manager = new ChallengeManager(temporary.resolve("challenges"), data);
            Challenge original = new Challenge("stone", "Stone", "BREAK_BLOCK", "STONE", 3, 12);
            manager.install(original, false);
            assertThrows(IOException.class, () -> manager.install(original, false));
            assertThrows(UnsupportedOperationException.class, () -> manager.getChallenges().clear());
            Files.writeString(manager.directory().resolve("broken.yml"), "id: []");
            assertThrows(IOException.class, manager::loadChallenges);
            assertEquals(original, manager.getChallenges().get("stone"));
            Files.delete(manager.directory().resolve("broken.yml"));
            manager.delete("stone");
            assertTrue(manager.getChallenges().isEmpty());
            assertFalse(Files.exists(manager.pathFor("stone")));
        }
    }

    @Test void refusesSymlinkedFilesAndMismatchedIds() throws Exception {
        try (PlayerDataService data = new PlayerDataService(new FileStorage(temporary.resolve("players")), 10)) {
            ChallengeManager manager = new ChallengeManager(temporary.resolve("challenges"), data);
            manager.loadChallenges();
            Path outside = temporary.resolve("outside.yml");
            Files.writeString(outside, ChallengeManager.serialize(new Challenge("stone", "Stone", "Break Stone", 3, 1)));
            Files.createSymbolicLink(manager.pathFor("stone"), outside);
            assertThrows(IOException.class, manager::loadChallenges);
            assertThrows(IOException.class, () -> manager.install(new Challenge("stone", "Stone", "Break Stone", 1, 1), true));
            assertThrows(IOException.class, () -> manager.delete("stone"));
            Files.delete(manager.pathFor("stone"));
            Files.writeString(manager.pathFor("wrong"), Files.readString(outside));
            assertThrows(IOException.class, manager::loadChallenges);
        }
    }
}
