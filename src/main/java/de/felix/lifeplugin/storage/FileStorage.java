package de.felix.lifeplugin.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomically replaces players.yml; retains the original legacy lives.yml untouched. */
public final class FileStorage implements Storage {
    private final Path folder;
    private final Path file;
    private final Map<UUID, PlayerData> records = new HashMap<>();
    private boolean closed;

    public FileStorage(File folder) { this(folder.toPath()); }

    public FileStorage(Path folder) {
        this.folder = folder.toAbsolutePath().normalize();
        this.file = this.folder.resolve("players.yml");
        try {
            Files.createDirectories(this.folder);
            if (Files.exists(file)) readPlayers(read(file));
            Path legacyFile = this.folder.resolve("lives.yml");
            if (Files.exists(legacyFile) && importLegacy(read(legacyFile))) write(records);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            throw new StorageException("Cannot initialize player file storage", e);
        }
    }

    private static YamlConfiguration read(Path path) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('\u0000');
        yaml.load(path.toFile()); // Unlike loadConfiguration, parse errors must not silently become empty data.
        return yaml;
    }

    private void readPlayers(YamlConfiguration yaml) {
        Object version = yaml.get("schema-version");
        if (!(version instanceof Integer number) || number != 1)
            throw new IllegalArgumentException("Unsupported player file schema");
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            if (yaml.contains("players")) throw new IllegalArgumentException("Invalid players section");
            return;
        }
        for (String id : players.getKeys(false)) {
            ConfigurationSection entry = players.getConfigurationSection(id);
            if (entry == null) throw new IllegalArgumentException("Invalid player entry");
            Object lives = entry.get("lives");
            Object language = entry.get("language");
            if (!(lives instanceof Integer value) || !(language instanceof String text))
                throw new IllegalArgumentException("Invalid player fields");
            Map<String, Integer> progress = new HashMap<>();
            ConfigurationSection section = entry.getConfigurationSection("challenge-progress");
            if (section == null && entry.contains("challenge-progress")) throw new IllegalArgumentException("Invalid challenge progress");
            if (section != null) for (String challenge : section.getKeys(false)) {
                Object amount = section.get(challenge);
                if (!(amount instanceof Integer count)) throw new IllegalArgumentException("Invalid challenge progress");
                progress.put(challenge, count);
            }
            Object rawCompleted = entry.get("completed-challenges");
            if (!(rawCompleted instanceof java.util.List<?> values))
                throw new IllegalArgumentException("Invalid completed challenges");
            Set<String> completed = new HashSet<>();
            for (Object valueCompleted : values) {
                if (!(valueCompleted instanceof String challenge)) throw new IllegalArgumentException("Invalid completed challenge");
                completed.add(challenge);
            }
            records.put(UUID.fromString(id), new PlayerData(value, text, progress, completed));
        }
    }

    private boolean importLegacy(YamlConfiguration yaml) {
        boolean changed = false;
        ConfigurationSection section = yaml.getConfigurationSection("lives");
        if (section == null) section = yaml;
        for (String key : section.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(key); }
            catch (IllegalArgumentException ignored) { continue; }
            if (records.containsKey(uuid)) continue;
            Object raw = section.get(key);
            if (!(raw instanceof Integer lives) || lives < 0)
                throw new IllegalArgumentException("Invalid legacy lives value");
            records.put(uuid, new PlayerData(lives, "en", Map.of(), Set.of()));
            changed = true;
        }
        return changed;
    }

    @Override public synchronized Optional<PlayerData> load(UUID uuid) {
        ensureOpen();
        return Optional.ofNullable(records.get(java.util.Objects.requireNonNull(uuid)));
    }

    @Override public synchronized void save(UUID uuid, PlayerData data) {
        ensureOpen();
        Map<UUID, PlayerData> next = new HashMap<>(records);
        next.put(java.util.Objects.requireNonNull(uuid), java.util.Objects.requireNonNull(data));
        try { write(next); }
        catch (IOException e) { throw new StorageException("Cannot atomically save player data", e); }
        records.clear();
        records.putAll(next); // Publish in memory only after the replacement succeeded.
    }

    private void write(Map<UUID, PlayerData> snapshot) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('\u0000');
        yaml.set("schema-version", 1);
        Map<String, Object> players = new LinkedHashMap<>();
        snapshot.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            PlayerData data = entry.getValue();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("lives", data.lives());
            fields.put("language", data.language());
            fields.put("challenge-progress", new LinkedHashMap<>(data.challengeProgress()));
            fields.put("completed-challenges", data.completedChallenges().stream().sorted().toList());
            players.put(entry.getKey().toString(), fields);
        });
        yaml.set("players", players);
        Path temporary = Files.createTempFile(folder, ".players-", ".tmp");
        try {
            byte[] bytes = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("Storage is closed"); }
    @Override public synchronized void close() { closed = true; }
}
