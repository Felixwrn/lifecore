package de.felix.lifeplugin.challenge;

import com.sun.net.httpserver.HttpServer;
import de.felix.lifeplugin.network.ContentChecks;
import de.felix.lifeplugin.network.SafeHttpClient;
import de.felix.lifeplugin.storage.FileStorage;
import de.felix.lifeplugin.storage.PlayerDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GitHubChallengeDownloaderTest {
    @TempDir Path temporary;
    private HttpServer server;
    private PlayerDataService data;
    private ChallengeManager manager;
    private GitHubChallengeDownloader downloader;
    private final SafeHttpClient http = new SafeHttpClient(Duration.ofSeconds(3), Set.of(), true);

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        data = new PlayerDataService(new FileStorage(temporary.resolve("players")), 10);
        manager = new ChallengeManager(temporary.resolve("challenges"), data);
        downloader = new GitHubChallengeDownloader(manager, http);
    }

    @AfterEach void tearDown() {
        if (server != null) server.stop(0);
        if (data != null) data.close();
    }

    @Test void installsVerifiedIndexThenSkipsExistingLocalEdits() throws Exception {
        byte[] yaml = yaml("stone", 5);
        respond("/stone.yml", yaml);
        String index = "{\"schemaVersion\":1,\"challenges\":[{\"id\":\"stone\",\"url\":\""
                + uri("/stone.yml") + "\",\"sha256\":\"" + ContentChecks.sha256(yaml) + "\"}]}";
        respond("/index.json", index.getBytes(StandardCharsets.UTF_8));
        assertEquals(new GitHubChallengeDownloader.SyncResult(1, 0), downloader.sync(uri("/index.json"), false));
        manager.install(new Challenge("stone", "Local", "Break Stone", 20, 7), true);
        assertEquals(new GitHubChallengeDownloader.SyncResult(0, 1), downloader.sync(uri("/index.json"), false));
        assertEquals("Local", manager.getChallenges().get("stone").getName());
        assertEquals(new GitHubChallengeDownloader.SyncResult(1, 0), downloader.sync(uri("/index.json"), true));
        assertEquals(5, manager.getChallenges().get("stone").getGoal());
    }

    @Test void badHashOrInvalidLastItemNeverPartiallyInstalls() throws Exception {
        byte[] valid = yaml("stone", 5);
        byte[] invalid = "id: invalid\ncommand: op player".getBytes(StandardCharsets.UTF_8);
        respond("/stone.yml", valid);
        respond("/invalid.yml", invalid);
        var good = new GitHubChallengeDownloader.Download("stone", uri("/stone.yml"), ContentChecks.sha256(valid));
        var badHash = new GitHubChallengeDownloader.Download("other", uri("/stone.yml"), "0".repeat(64));
        assertThrows(IOException.class, () -> downloader.sync(List.of(good, badHash), false));
        assertTrue(manager.getChallenges().isEmpty());
        assertFalse(Files.exists(manager.pathFor("stone")));
        var badSchema = new GitHubChallengeDownloader.Download("invalid", uri("/invalid.yml"), ContentChecks.sha256(invalid));
        assertThrows(IOException.class, () -> downloader.sync(List.of(good, badSchema), false));
        assertTrue(manager.getChallenges().isEmpty());
        assertFalse(Files.exists(manager.pathFor("stone")));
    }

    @Test void rejectsIdMismatchDuplicatesOversizedDataAndUntrustedUrl() throws Exception {
        byte[] valid = yaml("stone", 5);
        respond("/stone.yml", valid);
        var mismatch = new GitHubChallengeDownloader.Download("different", uri("/stone.yml"), ContentChecks.sha256(valid));
        assertThrows(IOException.class, () -> downloader.sync(List.of(mismatch), false));
        var good = new GitHubChallengeDownloader.Download("stone", uri("/stone.yml"), ContentChecks.sha256(valid));
        assertThrows(IOException.class, () -> downloader.sync(List.of(good, good), false));
        byte[] large = new byte[ChallengeManager.MAX_YAML_BYTES + 1];
        respond("/large.yml", large);
        var oversized = new GitHubChallengeDownloader.Download("large", uri("/large.yml"), ContentChecks.sha256(large));
        assertThrows(IOException.class, () -> downloader.sync(List.of(oversized), false));
        var untrusted = new GitHubChallengeDownloader.Download("remote", URI.create("https://untrusted.invalid/file.yml"), "0".repeat(64));
        assertThrows(IOException.class, () -> downloader.sync(List.of(untrusted), false));
        assertTrue(manager.getChallenges().isEmpty());
    }

    @Test void restoresOriginalFilesAndCacheWhenLaterInstallFails() throws Exception {
        ChallengeManager failing = new ChallengeManager(temporary.resolve("challenges"), data) {
            @Override public synchronized void install(Challenge challenge, boolean overwrite) throws IOException {
                if (challenge.getId().equals("fail")) throw new IOException("simulated disk write failure");
                super.install(challenge, overwrite);
            }
        };
        Challenge original = new Challenge("stone", "Local", "Break Stone", 40, 7);
        failing.install(original, false);
        byte[] originalBytes = Files.readAllBytes(failing.pathFor("stone"));
        byte[] first = yaml("stone", 5);
        byte[] second = yaml("fail", 2);
        respond("/first.yml", first);
        respond("/second.yml", second);
        var firstDownload = new GitHubChallengeDownloader.Download("stone", uri("/first.yml"), ContentChecks.sha256(first));
        var secondDownload = new GitHubChallengeDownloader.Download("fail", uri("/second.yml"), ContentChecks.sha256(second));
        assertThrows(IOException.class, () -> new GitHubChallengeDownloader(failing, http).sync(List.of(firstDownload, secondDownload), true));
        assertArrayEquals(originalBytes, Files.readAllBytes(failing.pathFor("stone")));
        assertEquals("Local", failing.getChallenges().get("stone").getName());
        assertFalse(Files.exists(failing.pathFor("fail")));
    }

    @Test void rejectsIndexSchemaAndMissingDigest() throws Exception {
        respond("/index.json", "{\"schemaVersion\":1,\"challenges\":[{\"id\":\"stone\",\"url\":\"https://example.com/a\"}]}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> downloader.sync(uri("/index.json"), false));
        assertThrows(IllegalArgumentException.class, () -> new GitHubChallengeDownloader.Download("../bad", uri("/a"), "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new GitHubChallengeDownloader.Download("bad", uri("/a"), ""));
    }

    private byte[] yaml(String id, int goal) {
        return ChallengeManager.serialize(new Challenge(id, "Stone", "Break Stone", goal, 12)).getBytes(StandardCharsets.UTF_8);
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }
    private void respond(String path, byte[] bytes) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
    }
}
