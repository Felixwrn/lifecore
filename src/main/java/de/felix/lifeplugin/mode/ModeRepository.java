package de.felix.lifeplugin.mode;

import com.google.gson.JsonObject;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Atomically persisted YAML modes; callers must explicitly opt into overwriting. */
public final class ModeRepository {
    private static final int MAX_FILE_BYTES = 16_384;
    private static final Set<String> LEGACY_BUILDER_FIELDS = Set.of("steal-on-kill", "steal-amount",
            "max-lives", "icon", "description");
    private final Path folder;
    private final Map<String, ModeDefinition> modes = new LinkedHashMap<>();

    public ModeRepository(Path folder) {
        this.folder = folder.toAbsolutePath().normalize();
    }

    public Path folder() { return folder; }

    public Path pathFor(String id) {
        return folder.resolve(ModeDefinition.validateId(id) + ".yml");
    }

    public synchronized void load() throws IOException {
        ensureFolder();
        Map<String, ModeDefinition> loaded = new LinkedHashMap<>();
        try (var paths = Files.list(folder)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted().toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES) {
                    throw new IOException("Mode file is not a regular bounded file: " + path.getFileName());
                }
                ModeDefinition mode;
                try {
                    String filename = path.getFileName().toString();
                    String id = ModeDefinition.validateId(filename.substring(0, filename.length() - 4));
                    mode = decode(readBounded(path), id);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid mode file: " + path.getFileName(), exception);
                }
                if (!path.getFileName().toString().equals(mode.id() + ".yml")) {
                    throw new IOException("Mode id does not match its filename: " + path.getFileName());
                }
                loaded.put(mode.id(), mode);
            }
        }
        modes.clear();
        modes.putAll(loaded);
    }

    public synchronized Optional<ModeDefinition> find(String id) {
        return Optional.ofNullable(modes.get(ModeDefinition.validateId(id)));
    }

    public synchronized List<ModeDefinition> all() {
        return modes.values().stream().sorted(Comparator.comparing(ModeDefinition::id)).toList();
    }

    public synchronized void save(ModeDefinition mode, boolean overwrite) throws IOException {
        if (mode == null) throw new IllegalArgumentException("Mode is required");
        ensureFolder();
        Path target = pathFor(mode.id());
        if (Files.isSymbolicLink(target)) throw new IOException("Refusing symbolic mode file");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing non-regular mode file");
        }
        if (!overwrite && (modes.containsKey(mode.id()) || Files.exists(target, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Mode already exists: " + mode.id());
        }
        byte[] bytes = encode(mode).getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(folder, ".mode-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Unsupported atomic moves fail explicitly rather than exposing partial files.
            if (overwrite) {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING.
                // A hard link publishes the fully flushed file atomically and fails if a
                // concurrent writer has already claimed the name. No unsafe fallback.
                Files.createLink(target, temporary);
            }
            modes.put(mode.id(), mode);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized void delete(String id) throws IOException {
        ensureFolder();
        Path path = pathFor(id);
        if (Files.isSymbolicLink(path)) throw new IOException("Refusing symbolic mode file");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing non-regular mode file");
        }
        Files.deleteIfExists(path);
        modes.remove(id);
    }

    private void ensureFolder() throws IOException {
        Files.createDirectories(folder);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || !folder.toRealPath().equals(folder)) {
            throw new IOException("Mode folder must be a real directory without symbolic links");
        }
    }

    private static String readBounded(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Mode file is too large");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static ModeDefinition decode(String text, String filenameId) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(4);
        options.setCodePointLimit(MAX_FILE_BYTES);
        Object parsed;
        try {
            parsed = new Yaml(new SafeConstructor(options)).load(text);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid mode YAML");
        }
        if (!(parsed instanceof Map<?, ?> root) || root.size() != 1 || !(root.get("mode") instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Expected the mode YAML section");
        }
        JsonObject object = new JsonObject();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Invalid YAML field");
            Object value = entry.getValue();
            if (value instanceof String string) object.addProperty(key, string);
            else if (value instanceof Boolean bool) object.addProperty(key, bool);
            else if (value instanceof Number number) object.addProperty(key, number);
            else throw new IllegalArgumentException("Invalid YAML field type");
        }
        // Compatibility is limited to the historical GUI builder local-file format.
        // The online JSON codec continues to require the entire current schema.
        if (object.keySet().containsAll(LEGACY_BUILDER_FIELDS)
                && object.keySet().stream().allMatch(key -> LEGACY_BUILDER_FIELDS.contains(key)
                || key.equals("lose-on-death"))) {
            object.addProperty("id", filenameId);
            object.addProperty("name", filenameId);
            int maximum = legacyInteger(values.get("max-lives"));
            object.addProperty("starting-lives", Math.min(3, maximum));
            if (!object.has("lose-on-death")) object.addProperty("lose-on-death", true);
            object.addProperty("ban-on-zero", false);
        }
        return ModeCodec.fromJson(object);
    }

    private static int legacyInteger(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Lives must be an integer");
        try {
            return new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Lives must be an integer within range");
        }
    }

    private static String encode(ModeDefinition mode) {
        Map<String, Object> values = new LinkedHashMap<>();
        ModeCodec.toJson(mode).entrySet().forEach(entry -> {
            var primitive = entry.getValue().getAsJsonPrimitive();
            values.put(entry.getKey(), primitive.isBoolean() ? primitive.getAsBoolean()
                    : primitive.isNumber() ? primitive.getAsInt() : primitive.getAsString());
        });
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(Map.of("mode", values));
    }
}
