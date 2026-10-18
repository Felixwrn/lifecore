package de.felix.lifeplugin.challenge;

import de.felix.lifeplugin.storage.PlayerData;
import de.felix.lifeplugin.storage.PlayerDataService;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local definitions and persisted, player-specific progress. Safe for worker-thread installs. */
public class ChallengeManager {
    public static final int MAX_YAML_BYTES = 16_384;
    private static final Set<String> FIELDS = Set.of("id", "name", "type", "target", "goal", "reward");
    private final Path folder;
    private final PlayerDataService data;
    private final Map<String, Challenge> challenges = new LinkedHashMap<>();

    public ChallengeManager(Path folder, PlayerDataService data) {
        this.folder = folder.toAbsolutePath().normalize();
        this.data = java.util.Objects.requireNonNull(data, "Player data service is required");
    }

    public Path directory() { return folder; }
    public Path folder() { return folder; }
    public Path pathFor(String id) { return folder.resolve(Challenge.validateId(id) + ".yml"); }

    /** All-or-nothing reload: a damaged definition never discards existing working definitions. */
    public synchronized void loadChallenges() throws IOException {
        ensureFolder();
        Map<String, Challenge> loaded = new LinkedHashMap<>();
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_YAML_BYTES) {
                    throw new IOException("Challenge is not a bounded, regular YAML file: " + file.getFileName());
                }
                Challenge challenge;
                try { challenge = parse(Files.readString(file, StandardCharsets.UTF_8)); }
                catch (IllegalArgumentException e) { throw new IOException("Invalid challenge: " + file.getFileName(), e); }
                if (!file.getFileName().toString().equals(challenge.getId() + ".yml")) {
                    throw new IOException("Challenge id does not match its filename");
                }
                loaded.put(challenge.getId(), challenge);
            }
        }
        challenges.clear();
        challenges.putAll(loaded);
    }

    public synchronized Map<String, Challenge> getChallenges() { return Map.copyOf(challenges); }

    /** Returns newly completed definitions exactly once, with their completion flags already cached. */
    public synchronized List<Challenge> progress(UUID player, String event, String target, int amount) {
        if (amount < 1 || amount > Challenge.MAX_GOAL) throw new IllegalArgumentException("Invalid challenge increment");
        if (!data.isLoaded(player)) return List.of();
        List<Challenge> completed = new ArrayList<>();
        data.update(player, snapshot -> {
            PlayerData updated = snapshot;
            for (Challenge challenge : challenges.values()) {
                if (!challenge.getType().equals(event) || !challenge.getTarget().equals(target)
                        || updated.completedChallenges().contains(challenge.getId())) continue;
                int previous = updated.challengeProgress().getOrDefault(challenge.getId(), 0);
                int progress = (int) Math.min(challenge.getGoal(), (long) previous + amount);
                updated = updated.withProgress(challenge.getId(), progress);
                if (progress >= challenge.getGoal()) {
                    updated = updated.completeChallenge(challenge.getId());
                    completed.add(challenge);
                }
            }
            return updated;
        });
        return List.copyOf(completed);
    }

    public synchronized void install(Challenge challenge, boolean overwrite) throws IOException {
        java.util.Objects.requireNonNull(challenge, "Challenge is required");
        ensureFolder();
        Path target = pathFor(challenge.getId());
        if (Files.isSymbolicLink(target)) throw new IOException("Refusing symbolic challenge file");
        if (!overwrite && (challenges.containsKey(challenge.getId()) || Files.exists(target, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Challenge already exists: " + challenge.getId());
        }
        Path temporary = Files.createTempFile(folder, ".challenge-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(serialize(challenge).getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            if (overwrite) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            challenges.put(challenge.getId(), challenge);
        } finally { Files.deleteIfExists(temporary); }
    }

    public synchronized void delete(String id) throws IOException {
        ensureFolder();
        Path target = pathFor(id);
        if (Files.isSymbolicLink(target)) throw new IOException("Refusing symbolic challenge file");
        Files.deleteIfExists(target);
        challenges.remove(id);
    }

    public static Challenge parse(String text) {
        if (text == null || text.length() > MAX_YAML_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_YAML_BYTES) {
            throw new IllegalArgumentException("Challenge YAML exceeds its allowed size");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(3);
        options.setCodePointLimit(MAX_YAML_BYTES);
        Object parsed;
        try { parsed = new Yaml(new SafeConstructor(options)).load(text); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid challenge YAML"); }
        if (!(parsed instanceof Map<?, ?> fields) || !FIELDS.containsAll(fields.keySet())) {
            throw new IllegalArgumentException("Expected declarative challenge fields");
        }
        String type = string(fields, "type");
        String target = "Break Stone".equals(type) && !fields.containsKey("target") ? "STONE" : string(fields, "target");
        return new Challenge(string(fields, "id"), string(fields, "name"), type, target,
                integer(fields, "goal"), integer(fields, "reward"));
    }

    public static String serialize(Challenge challenge) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", challenge.getId());
        fields.put("name", challenge.getName());
        fields.put("type", challenge.getType());
        fields.put("target", challenge.getTarget());
        fields.put("goal", challenge.getGoal());
        fields.put("reward", challenge.getReward());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(fields);
    }

    private static String string(Map<?, ?> fields, String name) {
        if (!(fields.get(name) instanceof String value)) throw new IllegalArgumentException("Invalid challenge text field: " + name);
        return value;
    }

    private static int integer(Map<?, ?> fields, String name) {
        if (!(fields.get(name) instanceof Integer value)) throw new IllegalArgumentException("Invalid challenge integer field: " + name);
        return value;
    }

    private void ensureFolder() throws IOException {
        Files.createDirectories(folder);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || !folder.toRealPath().equals(folder)) {
            throw new IOException("Challenge folder must be a real directory without symbolic links");
        }
    }
}
