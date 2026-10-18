package de.felix.lifeplugin.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageTest {
    @TempDir Path folder;

    @Test void immutableSnapshotsRejectAliasingAndInvalidData() {
        Map<String, Integer> progress = new HashMap<>(Map.of("stone", 2));
        Set<String> completed = new HashSet<>(Set.of("first"));
        PlayerData data = new PlayerData(3, "de", progress, completed);
        progress.put("stone", 10);
        completed.clear();
        assertEquals(2, data.challengeProgress().get("stone"));
        assertTrue(data.completedChallenges().contains("first"));
        assertThrows(UnsupportedOperationException.class, () -> data.challengeProgress().put("x", 1));
        PlayerData next = data.withLives(5).withLanguage("en").withProgress("stone", 4).completeChallenge("stone");
        assertEquals(3, data.lives());
        assertEquals(5, next.lives());
        assertEquals("en", next.language());
        assertTrue(next.completedChallenges().contains("stone"));
        assertThrows(IllegalArgumentException.class, () -> data.withLives(-1));
        assertThrows(IllegalArgumentException.class, () -> data.withProgress("../file", 2));
        assertThrows(IllegalArgumentException.class, () -> data.withProgress("stone", -1));
    }

    @Test void survivesRestartAndLegacyImportNeverOverwritesExistingData() throws Exception {
        UUID current = UUID.randomUUID(), legacy = UUID.randomUUID();
        PlayerData expected = new PlayerData(7, "de", Map.of("stone", 9, "daily.one", 3), Set.of("stone"));
        try (Storage storage = new FileStorage(folder)) {
            assertTrue(storage.load(current).isEmpty());
            storage.save(current, expected);
        }
        String old = current + ": 1\n" + legacy + ": 4\n";
        Files.writeString(folder.resolve("lives.yml"), old);
        try (Storage restarted = new FileStorage(folder)) {
            assertEquals(expected, restarted.load(current).orElseThrow());
            assertEquals(4, restarted.load(legacy).orElseThrow().lives());
        }
        assertEquals(old, Files.readString(folder.resolve("lives.yml")));
        try (Storage restarted = new FileStorage(folder)) {
            assertEquals(expected, restarted.load(current).orElseThrow());
            assertEquals(4, restarted.load(legacy).orElseThrow().lives());
        }
        try (var files = Files.list(folder)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"))); }
    }

    @Test void corruptFileFailsInsteadOfResettingIt() throws Exception {
        Path file = folder.resolve("players.yml");
        String corrupt = "schema-version: 1\nplayers: [this is not a mapping]\n";
        Files.writeString(file, corrupt);
        assertThrows(StorageException.class, () -> new FileStorage(folder));
        assertEquals(corrupt, Files.readString(file));
        Files.writeString(file, "schema-version: 99\nplayers: {}\n");
        assertThrows(StorageException.class, () -> new FileStorage(folder));
        Files.writeString(file, "schema-version: 1.5\nplayers: {}\n");
        assertThrows(StorageException.class, () -> new FileStorage(folder));
    }

    @Test void failedAtomicReplacementDoesNotPublishUnsavedSnapshot() throws Exception {
        UUID uuid = UUID.randomUUID();
        PlayerData before = new PlayerData(3, "en", Map.of(), Set.of());
        try (Storage storage = new FileStorage(folder)) {
            storage.save(uuid, before);
            Files.delete(folder.resolve("players.yml"));
            Files.createDirectory(folder.resolve("players.yml"));
            Files.writeString(folder.resolve("players.yml/keep"), "blocking replacement");
            assertThrows(StorageException.class, () -> storage.save(uuid, before.withLives(9)));
            assertEquals(before, storage.load(uuid).orElseThrow());
        }
    }
}
