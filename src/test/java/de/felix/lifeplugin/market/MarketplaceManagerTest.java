package de.felix.lifeplugin.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.felix.lifeplugin.challenge.Challenge;
import de.felix.lifeplugin.challenge.ChallengeManager;
import de.felix.lifeplugin.mode.ModeCodec;
import de.felix.lifeplugin.mode.ModeDefinition;
import de.felix.lifeplugin.mode.ModeRepository;
import de.felix.lifeplugin.network.ContentChecks;
import de.felix.lifeplugin.network.HttpFixture;
import de.felix.lifeplugin.network.SafeHttpClient;
import de.felix.lifeplugin.storage.FileStorage;
import de.felix.lifeplugin.storage.PlayerDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MarketplaceManagerTest {
    @TempDir Path folder;
    private static ModeDefinition mode(int lives) {
        return new ModeDefinition("starter", "Starter", "Safe starter mode", "STONE", lives, 20, true, true, 1, false);
    }
    private static JsonObject pack() {
        JsonObject pack = new JsonObject(); pack.addProperty("schemaVersion", 1);
        JsonArray modes = new JsonArray(); modes.add(ModeCodec.toJson(mode(10))); pack.add("modes", modes);
        JsonArray challenges = new JsonArray();
        JsonObject challenge = new JsonObject(); challenge.addProperty("id", "stone_miner"); challenge.addProperty("name", "Stone Miner");
        challenge.addProperty("type", "BREAK_BLOCK"); challenge.addProperty("target", "STONE"); challenge.addProperty("goal", 10); challenge.addProperty("reward", 5);
        challenges.add(challenge); pack.add("challenges", challenges); return pack;
    }
    private static void serve(HttpFixture fixture, JsonObject pack, String hashOverride) {
        byte[] bytes = pack.toString().getBytes(StandardCharsets.UTF_8); fixture.put("/pack.json", bytes);
        JsonObject entry = new JsonObject(); entry.addProperty("id", "starter-pack"); entry.addProperty("name", "Starter Pack");
        entry.addProperty("description", "A safe local fixture"); entry.addProperty("type", "pack"); entry.addProperty("version", "1.0.0");
        entry.addProperty("url", fixture.uri("/pack.json").toString()); entry.addProperty("sha256", hashOverride == null ? ContentChecks.sha256(bytes) : hashOverride);
        JsonObject catalog = new JsonObject(); catalog.addProperty("schemaVersion", 1); JsonArray entries = new JsonArray(); entries.add(entry); catalog.add("entries", entries);
        fixture.put("/catalog.json", catalog.toString());
    }
    private static MarketplaceManager manager(HttpFixture fixture, ModeRepository modes, ChallengeManager challenges) {
        return new MarketplaceManager(fixture.uri("/catalog.json"), new SafeHttpClient(Duration.ofSeconds(3), Set.of(), true), modes, challenges);
    }

    @Test void installsValidatedMixedPackAndPreservesExistingDataByDefault() throws Exception {
        try (HttpFixture fixture = new HttpFixture(); PlayerDataService data = new PlayerDataService(new FileStorage(folder.resolve("players")), 10)) {
            ModeRepository modes = new ModeRepository(folder.resolve("modes")); modes.load();
            ChallengeManager challenges = new ChallengeManager(folder.resolve("challenges"), data); challenges.loadChallenges();
            JsonObject pack = pack();
            pack.getAsJsonArray("challenges").add("id: oak_collector\nname: Oak Collector\ntype: BREAK_BLOCK\ntarget: OAK_LOG\ngoal: 5\nreward: 3\n");
            serve(fixture, pack, null); MarketplaceManager manager = manager(fixture, modes, challenges);
            assertEquals(1, manager.refresh().size());
            assertEquals(new MarketplaceManager.InstallResult("starter-pack", 1, 2), manager.install("starter-pack", false));
            assertEquals(mode(10), modes.find("starter").orElseThrow()); assertEquals(2, challenges.getChallenges().size());
            byte[] original = Files.readAllBytes(modes.pathFor("starter"));
            assertThrows(IOException.class, () -> manager.install("starter-pack", false));
            assertArrayEquals(original, Files.readAllBytes(modes.pathFor("starter")));
            assertEquals(1, manager.install("starter-pack", true).modeCount());
            assertThrows(IOException.class, () -> manager.install("../outside", true));
        }
    }

    @Test void checksumAndWholePackValidationPreventEveryWrite() throws Exception {
        try (HttpFixture fixture = new HttpFixture(); PlayerDataService data = new PlayerDataService(new FileStorage(folder.resolve("players")), 10)) {
            ModeRepository modes = new ModeRepository(folder.resolve("modes")); modes.load();
            ChallengeManager challenges = new ChallengeManager(folder.resolve("challenges"), data); challenges.loadChallenges();
            serve(fixture, pack(), "0".repeat(64)); MarketplaceManager manager = manager(fixture, modes, challenges); manager.refresh();
            assertThrows(IOException.class, () -> manager.install("starter-pack", false)); assertTrue(modes.all().isEmpty());
            JsonObject malicious = pack(); malicious.getAsJsonArray("challenges").get(0).getAsJsonObject().addProperty("id", "../escape");
            serve(fixture, malicious, null); manager.refresh();
            assertThrows(IOException.class, () -> manager.install("starter-pack", false)); assertTrue(modes.all().isEmpty()); assertTrue(challenges.getChallenges().isEmpty());
            malicious = pack(); malicious.getAsJsonArray("modes").get(0).getAsJsonObject().addProperty("command", "op attacker");
            serve(fixture, malicious, null); manager.refresh(); assertThrows(IOException.class, () -> manager.install("starter-pack", false));
            assertFalse(Files.exists(folder.resolve("modes/starter.yml"))); assertFalse(Files.exists(folder.resolve("escape.yml")));
        }
    }

    @Test void laterStorageFailureRestoresOverwrittenModeBytesAndCache() throws Exception {
        try (HttpFixture fixture = new HttpFixture(); PlayerDataService data = new PlayerDataService(new FileStorage(folder.resolve("players")), 10)) {
            ModeRepository modes = new ModeRepository(folder.resolve("modes")); modes.load(); modes.save(mode(5), false);
            byte[] original = Files.readAllBytes(modes.pathFor("starter"));
            ChallengeManager failing = new ChallengeManager(folder.resolve("challenges"), data) {
                @Override public synchronized void install(Challenge challenge, boolean overwrite) throws IOException { throw new IOException("Simulated disk write failure"); }
            };
            failing.loadChallenges(); serve(fixture, pack(), null); MarketplaceManager manager = manager(fixture, modes, failing); manager.refresh();
            IOException failure = assertThrows(IOException.class, () -> manager.install("starter-pack", true));
            assertEquals(0, failure.getSuppressed().length); assertArrayEquals(original, Files.readAllBytes(modes.pathFor("starter")));
            assertEquals(mode(5), modes.find("starter").orElseThrow()); assertFalse(Files.exists(failing.pathFor("stone_miner")));
            try (var files = Files.list(modes.folder())) { assertEquals(1, files.count()); }
        }
    }

    @Test void failedRefreshRetainsKnownGoodCatalogAndRejectsMissingHashes() throws Exception {
        try (HttpFixture fixture = new HttpFixture(); PlayerDataService data = new PlayerDataService(new FileStorage(folder.resolve("players")), 10)) {
            ModeRepository modes = new ModeRepository(folder.resolve("modes")); modes.load();
            ChallengeManager challenges = new ChallengeManager(folder.resolve("challenges"), data); challenges.loadChallenges();
            serve(fixture, pack(), null); MarketplaceManager manager = manager(fixture, modes, challenges); manager.refresh();
            serve(fixture, pack(), ""); assertThrows(IOException.class, manager::refresh); assertEquals(1, manager.entries().size());
            fixture.put("/catalog.json", "{\"schemaVersion\":1,\"entries\":[],\"entries\":[]}"); assertThrows(IOException.class, manager::refresh);
            assertEquals(1, manager.entries().size());
        }
    }
}
