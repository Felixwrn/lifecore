package de.felix.lifeplugin.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.felix.lifeplugin.network.ContentChecks;
import de.felix.lifeplugin.network.HttpStatusException;
import de.felix.lifeplugin.network.SafeHttpClient;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Verifies release metadata and stages a JAR for the next server restart. Never hot-reloads. */
public final class UpdateManager {
    private static final String MAIN = "de.felix.lifeplugin.Main";
    private static final int MAX_JAR_BYTES = 32 * 1024 * 1024;
    private final Version current;
    private final URI releases;
    private final SafeHttpClient http;
    private final Path updateFolder;
    private final String jarFileName;
    private UpdateInfo checked;

    public UpdateManager(String currentVersion, URI releases, SafeHttpClient http, Path updateFolder, String jarFileName) {
        current = Version.parse(currentVersion, true);
        this.releases = java.util.Objects.requireNonNull(releases);
        this.http = java.util.Objects.requireNonNull(http);
        this.updateFolder = java.util.Objects.requireNonNull(updateFolder).toAbsolutePath().normalize();
        if (jarFileName == null || !jarFileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,123}\\.jar") || jarFileName.contains("..")) {
            throw new IllegalArgumentException("Update JAR filename must be a simple .jar filename");
        }
        this.jarFileName = jarFileName;
    }

    public synchronized Optional<UpdateInfo> check() throws IOException, InterruptedException {
        checked = null;
        byte[] response;
        try { response = http.get(releases, 1024 * 1024); }
        catch (HttpStatusException failure) {
            if (failure.statusCode() == 404) return Optional.empty();
            throw failure;
        }
        JsonObject release = ContentChecks.object(response);
        if (bool(release, "draft") || bool(release, "prerelease")) return Optional.empty();
        String tag = ContentChecks.string(release, "tag_name", 64);
        Version version;
        try { version = Version.parse(tag, false); }
        catch (IllegalArgumentException failure) { throw new IOException("Release tag is not a stable semantic version"); }
        if (version.compareTo(current) <= 0) return Optional.empty();
        JsonElement assetArray = release.get("assets");
        if (assetArray == null || !assetArray.isJsonArray() || assetArray.getAsJsonArray().size() > 100) {
            throw new IOException("Release has no bounded asset list");
        }
        Map<String, URI> assets = new HashMap<>();
        Set<URI> urls = new HashSet<>();
        for (JsonElement value : assetArray.getAsJsonArray()) {
            if (!value.isJsonObject()) throw new IOException("Invalid release asset");
            JsonObject asset = value.getAsJsonObject();
            String name = ContentChecks.string(asset, "name", 128);
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || name.contains("..")) throw new IOException("Unsafe release asset name");
            URI url = uri(ContentChecks.string(asset, "browser_download_url", 2048));
            http.validate(url);
            validateGithubAsset(url, tag, name);
            if (assets.putIfAbsent(name, url) != null || !urls.add(url)) throw new IOException("Duplicate release asset");
        }
        URI manifestUrl = assets.get("lifecore-update.json");
        if (manifestUrl == null) return Optional.empty();
        JsonObject manifest = ContentChecks.object(http.get(manifestUrl, 16 * 1024));
        ContentChecks.fields(manifest, Set.of("schemaVersion", "version", "minecraft", "jarUrl", "sha256"));
        ContentChecks.schema(manifest);
        String manifestVersion = ContentChecks.string(manifest, "version", 64);
        if (!manifestVersion.equals(version.text())) throw new IOException("Release tag and update manifest versions differ");
        if (!ContentChecks.string(manifest, "minecraft", 16).equals("26.3")) return Optional.empty();
        URI jarUrl = uri(ContentChecks.string(manifest, "jarUrl", 2048));
        http.validate(jarUrl);
        boolean attached = assets.entrySet().stream().anyMatch(asset -> asset.getKey().endsWith(".jar") && asset.getValue().equals(jarUrl));
        if (!attached) throw new IOException("Update JAR must be an asset of the same release");
        checked = new UpdateInfo(version.text(), jarUrl, ContentChecks.hash(ContentChecks.string(manifest, "sha256", 64)));
        return Optional.of(checked);
    }

    public synchronized Path download(UpdateInfo update) throws IOException, InterruptedException {
        if (checked == null || !checked.equals(update)) throw new IOException("Check and validate this release before downloading it");
        byte[] bytes = http.get(update.jarUrl(), MAX_JAR_BYTES);
        ContentChecks.verify(bytes, update.sha256());
        Files.createDirectories(updateFolder);
        if (!updateFolder.toRealPath().equals(updateFolder) || !Files.isDirectory(updateFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Update folder must be a real directory without symbolic links");
        }
        Path target = updateFolder.resolve(jarFileName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe staged update file");
        }
        Path temporary = Files.createTempFile(updateFolder, ".lifecore-update-", ".tmp");
        try {
            Files.write(temporary, bytes);
            validateJar(temporary, update.version());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally { Files.deleteIfExists(temporary); }
    }

    private void validateGithubAsset(URI url, String tag, String name) throws IOException {
        if (!"api.github.com".equalsIgnoreCase(releases.getHost())) return;
        String path = releases.getPath();
        if (path == null || !path.matches("/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/latest")) {
            throw new IOException("GitHub updates require a latest-release API endpoint");
        }
        String[] parts = path.split("/");
        String expected = "/" + parts[2] + "/" + parts[3] + "/releases/download/" + tag + "/" + name;
        if (!"github.com".equalsIgnoreCase(url.getHost()) || !expected.equals(url.getPath()) || url.getRawQuery() != null) {
            throw new IOException("GitHub release asset points outside its tagged release");
        }
    }

    private static void validateJar(Path path, String expectedVersion) throws IOException {
        try (JarFile jar = new JarFile(path.toFile(), true)) {
            Set<String> names = new HashSet<>();
            long uncompressed = 0;
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!names.add(name) || names.size() > 15_000 || name.startsWith("/") || name.contains("\\")
                        || java.util.Arrays.asList(name.split("/")).contains("..") || entry.getSize() < 0) {
                    throw new IOException("Unsafe update archive");
                }
                uncompressed += entry.getSize();
                if (uncompressed > 128L * 1024 * 1024) throw new IOException("Update archive is too large");
            }
            JarEntry descriptor = jar.getJarEntry("plugin.yml");
            if (descriptor == null || descriptor.isDirectory() || descriptor.getSize() > 65_536) throw new IOException("Update JAR has no bounded plugin.yml");
            String yaml;
            try (InputStream input = jar.getInputStream(descriptor)) {
                byte[] bytes = input.readNBytes(65_537);
                if (bytes.length > 65_536) throw new IOException("Update plugin.yml is too large");
                yaml = ContentChecks.utf8(bytes);
            }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(12);
            options.setCodePointLimit(65_536);
            Object parsed = new Yaml(new SafeConstructor(options)).load(yaml);
            if (!(parsed instanceof Map<?, ?> plugin) || !"LifeCore".equals(plugin.get("name")) || !MAIN.equals(plugin.get("main"))
                    || !"26.3".equals(String.valueOf(plugin.get("api-version"))) || !expectedVersion.equals(String.valueOf(plugin.get("version")))) {
                throw new IOException("Update JAR is not the matching LifeCore release for Minecraft 26.3");
            }
            JarEntry main = jar.getJarEntry(MAIN.replace('.', '/') + ".class");
            if (main == null || main.isDirectory() || main.getSize() < 8 || main.getSize() > 4 * 1024 * 1024) {
                throw new IOException("Update JAR is missing the plugin main class");
            }
            try (InputStream input = jar.getInputStream(main)) {
                if (!java.util.Arrays.equals(input.readNBytes(4), new byte[] {(byte)0xca, (byte)0xfe, (byte)0xba, (byte)0xbe})) {
                    throw new IOException("Update main class is invalid");
                }
            }
        } catch (RuntimeException failure) { throw new IOException("Invalid update JAR metadata", failure); }
    }

    private static boolean bool(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IOException("Invalid release status");
        return value.getAsBoolean();
    }

    private static URI uri(String value) throws IOException {
        try { return URI.create(value); } catch (IllegalArgumentException failure) { throw new IOException("Invalid release URL"); }
    }

    public record UpdateInfo(String version, URI jarUrl, String sha256) { }

    private record Version(BigInteger major, BigInteger minor, BigInteger patch, String text) implements Comparable<Version> {
        static Version parse(String input, boolean allowTwoParts) {
            if (input == null || input.length() > 64) throw new IllegalArgumentException("Invalid semantic version");
            String value = input.startsWith("v") ? input.substring(1) : input;
            String number = "(0|[1-9][0-9]*)";
            if (allowTwoParts && value.matches(number + "\\." + number)) value += ".0";
            if (!value.matches(number + "\\." + number + "\\." + number)) throw new IllegalArgumentException("Expected a stable semantic version");
            String[] parts = value.split("\\.");
            return new Version(new BigInteger(parts[0]), new BigInteger(parts[1]), new BigInteger(parts[2]), value);
        }
        @Override public int compareTo(Version other) {
            int result = major.compareTo(other.major);
            if (result == 0) result = minor.compareTo(other.minor);
            if (result == 0) result = patch.compareTo(other.patch);
            return result;
        }
    }
}
