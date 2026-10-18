package de.felix.lifeplugin.challenge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.felix.lifeplugin.network.ContentChecks;
import de.felix.lifeplugin.network.SafeHttpClient;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, bounded challenge synchronization. Call only from a worker thread. */
public final class GitHubChallengeDownloader {
    private static final int MAX_CHALLENGES = 128;
    private final ChallengeManager manager;
    private final SafeHttpClient http;

    public GitHubChallengeDownloader(ChallengeManager manager, SafeHttpClient http) {
        this.manager = java.util.Objects.requireNonNull(manager);
        this.http = java.util.Objects.requireNonNull(http);
    }

    public record Download(String id, URI url, String sha256) {
        public Download {
            Challenge.validateId(id);
            java.util.Objects.requireNonNull(url, "Challenge URL is required");
            try { sha256 = ContentChecks.hash(sha256); }
            catch (IOException e) { throw new IllegalArgumentException("Challenge SHA-256 is required"); }
        }
    }

    public record SyncResult(int installed, int skipped) {}

    public SyncResult sync(URI index, boolean overwrite) throws IOException, InterruptedException {
        JsonObject object = ContentChecks.object(http.get(index, 256 * 1024));
        ContentChecks.fields(object, Set.of("schemaVersion", "challenges"));
        ContentChecks.schema(object);
        JsonElement items = object.get("challenges");
        if (items == null || !items.isJsonArray() || items.getAsJsonArray().size() > MAX_CHALLENGES) {
            throw new IOException("Challenge index must contain at most 128 challenge entries");
        }
        List<Download> downloads = new ArrayList<>();
        for (JsonElement item : items.getAsJsonArray()) {
            if (!item.isJsonObject()) throw new IOException("Invalid challenge index entry");
            JsonObject entry = item.getAsJsonObject();
            ContentChecks.fields(entry, Set.of("id", "url", "sha256"));
            try {
                downloads.add(new Download(ContentChecks.string(entry, "id", 48),
                        URI.create(ContentChecks.string(entry, "url", 2_048)),
                        ContentChecks.string(entry, "sha256", 64)));
            } catch (IllegalArgumentException e) { throw new IOException("Invalid challenge index entry", e); }
        }
        return sync(downloads, overwrite);
    }

    public SyncResult sync(List<Download> downloads, boolean overwrite) throws IOException, InterruptedException {
        if (downloads == null || downloads.size() > MAX_CHALLENGES) throw new IOException("Too many challenge downloads");
        Set<String> ids = new HashSet<>();
        Map<String, Challenge> parsed = new LinkedHashMap<>();
        Map<String, Challenge> current = manager.getChallenges();
        int skipped = 0;
        for (Download download : List.copyOf(downloads)) {
            if (!ids.add(download.id())) throw new IOException("Duplicate challenge id in index");
            http.validate(download.url());
            if (!overwrite && (current.containsKey(download.id())
                    || Files.exists(manager.pathFor(download.id()), LinkOption.NOFOLLOW_LINKS))) {
                skipped++;
                continue;
            }
            byte[] bytes = http.get(download.url(), ChallengeManager.MAX_YAML_BYTES);
            ContentChecks.verify(bytes, download.sha256());
            Challenge challenge;
            try { challenge = ChallengeManager.parse(ContentChecks.utf8(bytes)); }
            catch (IllegalArgumentException e) { throw new IOException("Downloaded challenge is invalid", e); }
            if (!download.id().equals(challenge.getId())) throw new IOException("Downloaded challenge id differs from index");
            parsed.put(challenge.getId(), challenge);
        }
        // Validate every download before the first write, and serialize with local/marketplace installs.
        synchronized (manager) {
            Map<String, byte[]> previous = new LinkedHashMap<>();
            List<String> installed = new ArrayList<>();
            try {
                for (Challenge challenge : parsed.values()) {
                    Path path = manager.pathFor(challenge.getId());
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                || Files.size(path) > ChallengeManager.MAX_YAML_BYTES) {
                            throw new IOException("Existing challenge is not a bounded regular file");
                        }
                        if (!overwrite) { skipped++; continue; }
                        previous.put(challenge.getId(), Files.readAllBytes(path));
                    } else previous.put(challenge.getId(), null);
                    manager.install(challenge, overwrite);
                    installed.add(challenge.getId());
                }
            } catch (IOException | RuntimeException failure) {
                IOException rollbackFailure = null;
                for (int i = installed.size() - 1; i >= 0; i--) {
                    String id = installed.get(i);
                    try {
                        byte[] backup = previous.get(id);
                        if (backup == null) manager.delete(id);
                        else restore(manager.pathFor(id), backup);
                    } catch (IOException e) {
                        if (rollbackFailure == null) rollbackFailure = e;
                        else rollbackFailure.addSuppressed(e);
                    }
                }
                try { manager.loadChallenges(); }
                catch (IOException e) {
                    if (rollbackFailure == null) rollbackFailure = e;
                    else rollbackFailure.addSuppressed(e);
                }
                if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
                throw failure;
            }
            return new SyncResult(installed.size(), skipped);
        }
    }

    private static void restore(Path destination, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".challenge-rollback-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
