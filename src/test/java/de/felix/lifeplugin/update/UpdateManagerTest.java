package de.felix.lifeplugin.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.felix.lifeplugin.network.ContentChecks;
import de.felix.lifeplugin.network.HttpFixture;
import de.felix.lifeplugin.network.SafeHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class UpdateManagerTest {
    @TempDir Path folder;
    private UpdateManager manager(HttpFixture fixture, String current) {
        return new UpdateManager(current, fixture.uri("/latest"), new SafeHttpClient(Duration.ofSeconds(3), Set.of(), true), folder.resolve("update"), "LifeCore.jar");
    }
    private static byte[] jar(String name, String main, String api, String version, boolean mainClass, boolean traversal) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(result)) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write(("name: " + name + "\nmain: " + main + "\napi-version: '" + api + "'\nversion: '" + version + "'\n").getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
            if (mainClass) {
                jar.putNextEntry(new JarEntry("de/felix/lifeplugin/Main.class"));
                try (var input = UpdateManager.class.getResourceAsStream("/de/felix/lifeplugin/Main.class")) {
                    assertNotNull(input); input.transferTo(jar);
                }
                jar.closeEntry();
            }
            if (traversal) { jar.putNextEntry(new JarEntry("../escape")); jar.write(1); jar.closeEntry(); }
        }
        return result.toByteArray();
    }
    private static JsonObject manifest(HttpFixture fixture, String version, byte[] jar) {
        JsonObject manifest = new JsonObject(); manifest.addProperty("schemaVersion", 1); manifest.addProperty("version", version);
        manifest.addProperty("minecraft", "26.3"); manifest.addProperty("jarUrl", fixture.uri("/LifeCore.jar").toString()); manifest.addProperty("sha256", ContentChecks.sha256(jar)); return manifest;
    }
    private static void serve(HttpFixture fixture, String version, byte[] jar, JsonObject manifest) {
        JsonObject release = new JsonObject(); release.addProperty("tag_name", "v" + version); release.addProperty("draft", false); release.addProperty("prerelease", false);
        JsonArray assets = new JsonArray();
        JsonObject metadata = new JsonObject(); metadata.addProperty("name", "lifecore-update.json"); metadata.addProperty("browser_download_url", fixture.uri("/manifest").toString()); assets.add(metadata);
        JsonObject binary = new JsonObject(); binary.addProperty("name", "LifeCore.jar"); binary.addProperty("browser_download_url", fixture.uri("/LifeCore.jar").toString()); assets.add(binary);
        release.add("assets", assets); fixture.put("/latest", release.toString()); fixture.put("/manifest", manifest.toString()); fixture.put("/LifeCore.jar", jar);
    }
    private static byte[] validJar(String version) throws IOException { return jar("LifeCore", "de.felix.lifeplugin.Main", "26.3", version, true, false); }

    @Test void checksNumericVersionAndStagesVerifiedJarWithoutChangingRunningPlugin() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            byte[] jar = validJar("2.10.0"); serve(fixture, "2.10.0", jar, manifest(fixture, "2.10.0", jar));
            UpdateManager manager = manager(fixture, "2.9.0"); UpdateManager.UpdateInfo info = manager.check().orElseThrow();
            assertEquals("2.10.0", info.version()); Path staged = manager.download(info);
            assertEquals(folder.resolve("update/LifeCore.jar"), staged); assertArrayEquals(jar, Files.readAllBytes(staged));
            assertFalse(Files.exists(folder.resolve("LifeCore.jar")));
            try (var files = Files.list(staged.getParent())) { assertEquals(1, files.count()); }
        }
    }

    @Test void missingReleaseAndCurrentVersionReturnEmpty() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            UpdateManager manager = manager(fixture, "2.1.0"); assertTrue(manager.check().isEmpty());
            byte[] jar = validJar("2.1.0"); serve(fixture, "2.1.0", jar, manifest(fixture, "2.1.0", jar)); assertTrue(manager.check().isEmpty());
            fixture.put("/latest", "{\"draft\":false,\"prerelease\":true,\"tag_name\":\"v3.0.0-beta\"}"); assertTrue(manager.check().isEmpty());
            fixture.status("/latest", 500); assertThrows(IOException.class, manager::check);
        }
    }

    @Test void rejectsUnattachedJarAndMismatchedManifestMetadata() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            byte[] jar = validJar("2.2.0"); JsonObject manifest = manifest(fixture, "2.2.0", jar);
            manifest.addProperty("jarUrl", fixture.uri("/unattached.jar").toString()); serve(fixture, "2.2.0", jar, manifest);
            UpdateManager manager = manager(fixture, "2.1.0"); assertThrows(IOException.class, manager::check);
            manifest = manifest(fixture, "2.3.0", jar); serve(fixture, "2.2.0", jar, manifest); assertThrows(IOException.class, manager::check);
            manifest = manifest(fixture, "2.2.0", jar); manifest.addProperty("sha256", ""); serve(fixture, "2.2.0", jar, manifest); assertThrows(IOException.class, manager::check);
            manifest = manifest(fixture, "2.2.0", jar); manifest.addProperty("minecraft", "26.2"); serve(fixture, "2.2.0", jar, manifest); assertTrue(manager.check().isEmpty());
            manifest = manifest(fixture, "2.2.0", jar); manifest.addProperty("command", "run code"); serve(fixture, "2.2.0", jar, manifest); assertThrows(IOException.class, manager::check);
            assertThrows(IOException.class, () -> manager.download(new UpdateManager.UpdateInfo("2.2.0", fixture.uri("/LifeCore.jar"), ContentChecks.sha256(jar))));
        }
    }

    @Test void badDigestPreservesExistingStagedJar() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            byte[] jar = validJar("2.2.0"); JsonObject manifest = manifest(fixture, "2.2.0", jar); manifest.addProperty("sha256", "0".repeat(64)); serve(fixture, "2.2.0", jar, manifest);
            Path existing = folder.resolve("update/LifeCore.jar"); Files.createDirectories(existing.getParent()); Files.writeString(existing, "previous verified update");
            UpdateManager manager = manager(fixture, "2.1.0"); var info = manager.check().orElseThrow(); assertThrows(IOException.class, () -> manager.download(info));
            assertEquals("previous verified update", Files.readString(existing));
        }
    }

    @Test void rejectsWrongPluginApiVersionMainClassAndArchivePaths() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            byte[][] invalid = {
                    jar("OtherPlugin", "de.felix.lifeplugin.Main", "26.3", "2.2.0", true, false),
                    jar("LifeCore", "attacker.Main", "26.3", "2.2.0", true, false),
                    jar("LifeCore", "de.felix.lifeplugin.Main", "26.2", "2.2.0", true, false),
                    jar("LifeCore", "de.felix.lifeplugin.Main", "26.3", "2.3.0", true, false),
                    jar("LifeCore", "de.felix.lifeplugin.Main", "26.3", "2.2.0", false, false),
                    jar("LifeCore", "de.felix.lifeplugin.Main", "26.3", "2.2.0", true, true),
                    "not a JAR".getBytes(StandardCharsets.UTF_8)
            };
            UpdateManager manager = manager(fixture, "2.1.0");
            for (byte[] jar : invalid) {
                serve(fixture, "2.2.0", jar, manifest(fixture, "2.2.0", jar)); var info = manager.check().orElseThrow();
                assertThrows(IOException.class, () -> manager.download(info)); assertFalse(Files.exists(folder.resolve("update/LifeCore.jar")));
            }
            try (var files = Files.list(folder.resolve("update"))) { assertEquals(0, files.count()); }
        }
    }

    @Test void constructorRejectsPathTraversal() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            assertThrows(IllegalArgumentException.class, () -> new UpdateManager("2.1.0", fixture.uri("/latest"), new SafeHttpClient(Duration.ofSeconds(1), Set.of(), true), folder, "../LifeCore.jar"));
        }
    }
}
