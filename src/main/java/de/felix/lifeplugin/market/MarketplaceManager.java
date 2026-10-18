package de.felix.lifeplugin.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.felix.lifeplugin.challenge.Challenge;
import de.felix.lifeplugin.challenge.ChallengeManager;
import de.felix.lifeplugin.mode.ModeCodec;
import de.felix.lifeplugin.mode.ModeDefinition;
import de.felix.lifeplugin.mode.ModeRepository;
import de.felix.lifeplugin.network.ContentChecks;
import de.felix.lifeplugin.network.SafeHttpClient;
import org.yaml.snakeyaml.Yaml;

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

/** A data-only marketplace. Call network methods from a worker thread. */
public final class MarketplaceManager {
    private static final int MAX_CATALOG_BYTES = 512 * 1024;
    private static final int MAX_PACK_BYTES = 2 * 1024 * 1024;
    private final URI catalog;
    private final SafeHttpClient http;
    private final ModeRepository modes;
    private final ChallengeManager challenges;
    private volatile List<CatalogEntry> entries = List.of();

    public MarketplaceManager(URI catalog, SafeHttpClient http, ModeRepository modes, ChallengeManager challenges) {
        this.catalog = java.util.Objects.requireNonNull(catalog);
        this.http = java.util.Objects.requireNonNull(http);
        this.modes = java.util.Objects.requireNonNull(modes);
        this.challenges = java.util.Objects.requireNonNull(challenges);
    }

    /** Replaces the cached catalogue only after every entry has been validated. */
    public synchronized List<CatalogEntry> refresh() throws IOException, InterruptedException {
        JsonObject root = ContentChecks.object(http.get(catalog, MAX_CATALOG_BYTES));
        ContentChecks.fields(root, Set.of("schemaVersion", "entries"));
        ContentChecks.schema(root);
        JsonArray array = array(root, "entries", 500);
        List<CatalogEntry> loaded = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement value : array) {
            if (!value.isJsonObject()) throw new IOException("Invalid catalogue entry");
            JsonObject object = value.getAsJsonObject();
            ContentChecks.fields(object, Set.of("id", "name", "description", "type", "version", "url", "sha256"));
            String id = id(ContentChecks.string(object, "id", 48));
            if (!ids.add(id)) throw new IOException("Duplicate catalogue id");
            String type = ContentChecks.string(object, "type", 8);
            if (!type.equals("mode") && !type.equals("pack")) throw new IOException("Unsupported catalogue type");
            URI url = uri(ContentChecks.string(object, "url", 2048));
            http.validate(url);
            loaded.add(new CatalogEntry(id, ContentChecks.string(object, "name", 64),
                    ContentChecks.string(object, "description", 1024), type,
                    ContentChecks.string(object, "version", 32), url,
                    ContentChecks.hash(ContentChecks.string(object, "sha256", 64))));
        }
        entries = List.copyOf(loaded);
        return entries;
    }

    public List<CatalogEntry> entries() { return entries; }

    public InstallResult install(String id, boolean overwrite) throws IOException, InterruptedException {
        id(id);
        CatalogEntry entry = entries.stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new IOException("Unknown marketplace entry; refresh the catalogue first"));
        byte[] bytes = http.get(entry.url(), MAX_PACK_BYTES);
        ContentChecks.verify(bytes, entry.sha256());
        Pack pack = parsePack(bytes);
        if (entry.type().equals("mode") && (pack.modes().size() != 1 || !pack.challenges().isEmpty())) {
            throw new IOException("A mode catalogue entry must contain exactly one mode");
        }
        synchronized (modes) {
            synchronized (challenges) {
                installTransaction(pack, overwrite);
            }
        }
        return new InstallResult(entry.id(), pack.modes().size(), pack.challenges().size());
    }

    private void installTransaction(Pack pack, boolean overwrite) throws IOException {
        Map<Path, byte[]> backups = new LinkedHashMap<>();
        for (ModeDefinition mode : pack.modes()) backup(backups, modes.pathFor(mode.id()), overwrite);
        for (Challenge challenge : pack.challenges()) {
            backup(backups, challenges.directory().resolve(id(challenge.getId()) + ".yml"), overwrite);
        }
        try {
            for (ModeDefinition mode : pack.modes()) modes.save(mode, overwrite);
            for (Challenge challenge : pack.challenges()) challenges.install(challenge, overwrite);
        } catch (IOException | RuntimeException failure) {
            IOException result = new IOException("Pack installation failed; restoring the previous data", failure);
            for (var backup : backups.entrySet()) {
                try { restore(backup.getKey(), backup.getValue()); }
                catch (IOException rollback) { result.addSuppressed(rollback); }
            }
            try { modes.load(); } catch (IOException rollback) { result.addSuppressed(rollback); }
            try { challenges.loadChallenges(); } catch (IOException rollback) { result.addSuppressed(rollback); }
            if (result.getSuppressed().length != 0) {
                throw new IOException("Pack installation failed and rollback could not finish; inspect server storage", result);
            }
            throw result;
        }
    }

    private static void backup(Map<Path, byte[]> backups, Path path, boolean overwrite) throws IOException {
        Path folder = path.getParent();
        Files.createDirectories(folder);
        if (!folder.toRealPath().equals(folder.toAbsolutePath().normalize())) throw new IOException("Symbolic storage path refused");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!overwrite) throw new IOException("Local data already exists: " + path.getFileName());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_PACK_BYTES) {
                throw new IOException("Unsafe local data file");
            }
            backups.put(path, Files.readAllBytes(path));
        } else backups.put(path, null);
    }

    private static void restore(Path target, byte[] original) throws IOException {
        if (original == null) { Files.deleteIfExists(target); return; }
        Path temporary = Files.createTempFile(target.getParent(), ".rollback-", ".tmp");
        try {
            Files.write(temporary, original);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Pack parsePack(byte[] bytes) throws IOException {
        JsonObject root = ContentChecks.object(bytes);
        ContentChecks.fields(root, Set.of("schemaVersion", "modes", "challenges"));
        ContentChecks.schema(root);
        List<ModeDefinition> modes = new ArrayList<>();
        List<Challenge> challenges = new ArrayList<>();
        Set<String> modeIds = new HashSet<>(), challengeIds = new HashSet<>();
        try {
            for (JsonElement value : array(root, "modes", 100)) {
                if (!value.isJsonObject()) throw new IOException("Invalid mode in pack");
                ModeDefinition mode = ModeCodec.fromJson(value.getAsJsonObject());
                if (!modeIds.add(mode.id())) throw new IOException("Duplicate pack mode id");
                modes.add(mode);
            }
            for (JsonElement value : array(root, "challenges", 100)) {
                Challenge challenge;
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    if (value.getAsString().length() > 16_384) throw new IOException("Challenge is too large");
                    challenge = ChallengeManager.parse(value.getAsString());
                } else if (value.isJsonObject()) {
                    JsonObject object = value.getAsJsonObject();
                    ContentChecks.fields(object, Set.of("id", "name", "type", "target", "goal", "reward"));
                    Map<String, Object> fields = new LinkedHashMap<>();
                    for (String field : List.of("id", "name", "type", "target")) {
                        fields.put(field, ContentChecks.string(object, field, 64));
                    }
                    for (String field : List.of("goal", "reward")) {
                        JsonElement number = object.get(field);
                        if (number == null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) {
                            throw new IOException("Invalid challenge number");
                        }
                        fields.put(field, number.getAsBigDecimal().intValueExact());
                    }
                    challenge = ChallengeManager.parse(new Yaml().dump(fields));
                } else throw new IOException("Invalid challenge in pack");
                if (!challengeIds.add(id(challenge.getId()))) throw new IOException("Duplicate pack challenge id");
                challenges.add(challenge);
            }
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new IOException("Invalid declarative content in pack", failure);
        }
        if (modes.isEmpty() && challenges.isEmpty()) throw new IOException("Pack is empty");
        return new Pack(List.copyOf(modes), List.copyOf(challenges));
    }

    private static JsonArray array(JsonObject root, String key, int maximum) throws IOException {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > maximum) {
            throw new IOException("Missing or oversized " + key + " array");
        }
        return value.getAsJsonArray();
    }

    private static String id(String value) throws IOException {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,47}")) throw new IOException("Unsafe content id");
        return value;
    }

    private static URI uri(String value) throws IOException {
        try { return URI.create(value); } catch (IllegalArgumentException failure) { throw new IOException("Invalid download URL"); }
    }

    public record CatalogEntry(String id, String name, String description, String type, String version, URI url, String sha256) { }
    public record InstallResult(String id, int modeCount, int challengeCount) { }
    private record Pack(List<ModeDefinition> modes, List<Challenge> challenges) { }
}
