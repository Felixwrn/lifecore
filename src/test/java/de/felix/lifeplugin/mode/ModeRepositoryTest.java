package de.felix.lifeplugin.mode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModeRepositoryTest {
    @TempDir Path temporary;

    private static ModeDefinition mode(String id, String description) {
        return new ModeDefinition(id, "Example " + id, description, "DIAMOND", 3, 10, true, true, 1, false);
    }

    @Test
    void persistsRoundTripSortsAndDeletesForTransactionRollback() throws IOException {
        ModeRepository repository = new ModeRepository(temporary.resolve("modes"));
        repository.load();
        var first = mode("b", "line one\nline two ✓");
        var second = mode("a", "Colon: and # are data");
        repository.save(first, false);
        repository.save(second, false);
        assertEquals(List.of(second, first), repository.all());
        assertThrows(UnsupportedOperationException.class, () -> repository.all().clear());

        ModeRepository reloaded = new ModeRepository(repository.folder());
        reloaded.load();
        assertEquals(repository.all(), reloaded.all());
        reloaded.delete("a");
        assertFalse(Files.exists(repository.pathFor("a")));
        assertTrue(reloaded.find("a").isEmpty());
        reloaded.delete("a");
        reloaded.save(second, false);
        assertEquals(second, reloaded.find("a").orElseThrow());
    }

    @Test
    void explicitOverwriteReplacesAtomicallyAndRefusalPreservesDiskAndCache() throws IOException {
        ModeRepository repository = new ModeRepository(temporary.resolve("modes"));
        var old = mode("example", "original");
        var updated = mode("example", "updated");
        repository.save(old, false);
        byte[] original = Files.readAllBytes(repository.pathFor("example"));
        assertThrows(IOException.class, () -> repository.save(updated, false));
        assertArrayEquals(original, Files.readAllBytes(repository.pathFor("example")));
        assertEquals(old, repository.find("example").orElseThrow());

        // A different repository has no cached copy; on-disk collisions still refuse.
        ModeRepository uncached = new ModeRepository(repository.folder());
        assertThrows(IOException.class, () -> uncached.save(updated, false));
        assertArrayEquals(original, Files.readAllBytes(repository.pathFor("example")));
        assertTrue(uncached.all().isEmpty());

        repository.save(updated, true);
        repository.load();
        assertEquals(updated, repository.find("example").orElseThrow());
        try (var files = Files.list(repository.folder())) {
            assertEquals(List.of("example.yml"), files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void pathTraversalSymbolicLinksAndNonRegularTargetsAreRefused() throws IOException {
        ModeRepository repository = new ModeRepository(temporary.resolve("modes"));
        repository.load();
        for (String id : new String[]{"../outside", "/absolute", "a/b", "a\\b", "A"}) {
            assertThrows(IllegalArgumentException.class, () -> repository.pathFor(id));
            assertThrows(IllegalArgumentException.class, () -> repository.delete(id));
        }
        Path external = temporary.resolve("outside.yml");
        Files.writeString(external, "outside");
        Files.createSymbolicLink(repository.pathFor("symbolic"), external);
        assertThrows(IOException.class, () -> repository.save(mode("symbolic", "data"), true));
        assertThrows(IOException.class, () -> repository.delete("symbolic"));
        assertThrows(IOException.class, repository::load);
        assertEquals("outside", Files.readString(external));
        Files.delete(repository.pathFor("symbolic"));

        Files.createDirectory(repository.pathFor("directory"));
        assertThrows(IOException.class, () -> repository.save(mode("directory", "data"), true));
        assertThrows(IOException.class, () -> repository.delete("directory"));
        assertTrue(repository.find("directory").isEmpty());
        Path link = temporary.resolve("linked-modes");
        Files.createSymbolicLink(link, repository.folder());
        assertThrows(IOException.class, () -> new ModeRepository(link).save(mode("escape", "data"), false));
    }

    @Test
    void failedLoadLeavesPreviouslyLoadedSnapshotIntact() throws IOException {
        ModeRepository repository = new ModeRepository(temporary.resolve("modes"));
        var original = mode("original", "present");
        repository.save(original, false);
        Files.writeString(repository.pathFor("invalid"), "mode: {id: invalid}");
        assertThrows(IOException.class, repository::load);
        assertEquals(List.of(original), repository.all());
    }

    @Test
    void invalidYamlTypesDuplicatesUnknownFieldsAndLargeFilesAreRejected() throws IOException {
        ModeRepository repository = new ModeRepository(temporary.resolve("modes"));
        repository.load();
        Path target = repository.pathFor("example");
        for (String content : new String[]{
                legacy(10) + "  max-lives: 20\n",
                legacy(10).replace("max-lives: 10", "max-lives: '10'"),
                legacy(10).replace("steal-on-kill: true", "steal-on-kill: 'true'"),
                legacy(10) + "  commands: 'op attacker'\n",
                legacy(10).replace("icon: DIAMOND", "icon: [DIAMOND]"),
                legacy(10).replace("description: Old builder", "description: null"),
                legacy(10) + "other: true\n",
                "mode: &rules {max-lives: 10}\nother: *rules\n",
                legacy(10) + "---\nmode: {}\n",
                "x".repeat(16_385)}) {
            Files.writeString(target, content);
            assertThrows(IOException.class, repository::load, content.substring(0, Math.min(content.length(), 100)));
        }
        Files.write(target, new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, repository::load);
    }

    @Test
    void oldBuilderFilesImportUsingSafeFilenameAndDocumentedDefaultsWithoutRewriting() throws IOException {
        ModeRepository repository = new ModeRepository(temporary.resolve("modes"));
        repository.load();
        String legacy = legacy(10);
        Files.writeString(repository.pathFor("old_builder"), legacy, StandardCharsets.UTF_8);
        repository.load();
        var imported = repository.find("old_builder").orElseThrow();
        assertEquals("old_builder", imported.id());
        assertEquals("old_builder", imported.name());
        assertEquals(3, imported.startingLives());
        assertEquals(10, imported.maxLives());
        assertTrue(imported.loseOnDeath());
        assertFalse(imported.banOnZero());
        assertEquals(legacy, Files.readString(repository.pathFor("old_builder")));
        repository.save(imported, true);
        assertTrue(Files.readString(repository.pathFor("old_builder")).contains("starting-lives: 3"));
        repository.load();
        assertEquals(imported, repository.find("old_builder").orElseThrow());
    }

    @Test
    void legacySmallMaxAndOptionalLoseOnDeathArePreserved() throws IOException {
        ModeRepository repository = new ModeRepository(temporary.resolve("modes"));
        repository.load();
        Files.writeString(repository.pathFor("one_life"), legacy(1) + "  lose-on-death: false\n");
        repository.load();
        var mode = repository.find("one_life").orElseThrow();
        assertEquals(1, mode.startingLives());
        assertFalse(mode.loseOnDeath());
        Files.writeString(repository.folder().resolve("unsafe name.yml"), legacy(10));
        assertThrows(IOException.class, repository::load);
    }

    @Test
    void canonicalIdMustMatchFilename() throws IOException {
        ModeRepository repository = new ModeRepository(temporary.resolve("modes"));
        repository.save(mode("actual", "data"), false);
        Files.move(repository.pathFor("actual"), repository.pathFor("other"));
        assertThrows(IOException.class, repository::load);
    }

    private static String legacy(int maximum) {
        return """
                mode:
                  steal-on-kill: true
                  steal-amount: 1
                  max-lives: %d
                  icon: DIAMOND
                  description: Old builder
                """.formatted(maximum);
    }
}
