package de.felix.lifeplugin.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

public final class ContentChecks {
    private ContentChecks() {}

    public static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IOException("Remote data is not valid UTF-8", e); }
    }

    public static JsonObject object(byte[] bytes) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(utf8(bytes)))) {
            reader.setLenient(false);
            JsonElement parsed = read(reader, 0);
            if (!parsed.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Expected one JSON object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException | StackOverflowError e) { throw new IOException("Invalid remote JSON", e); }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 20) throw new IOException("Remote JSON is nested too deeply");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate remote JSON field");
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid remote JSON token");
        };
    }

    public static void fields(JsonObject object, Set<String> fields) throws IOException {
        if (!fields.containsAll(object.keySet())) throw new IOException("Unsupported remote data fields");
    }

    public static String string(JsonObject object, String key, int maxLength) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Missing or invalid text field: " + key);
        }
        String result = value.getAsString();
        if (result.isBlank() || result.length() > maxLength || result.chars().anyMatch(c -> c < 32 && c != 10 && c != 13 && c != 9)) {
            throw new IOException("Text field exceeds its allowed range: " + key);
        }
        return result;
    }

    public static void schema(JsonObject object) throws IOException {
        JsonElement version = object.get("schemaVersion");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                || !version.getAsString().equals("1")) throw new IOException("Unsupported remote schema version");
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String hash(String hash) throws IOException {
        if (hash == null || !hash.matches("[a-fA-F0-9]{64}")) throw new IOException("A SHA-256 digest is required");
        return hash.toLowerCase(java.util.Locale.ROOT);
    }

    public static void verify(byte[] bytes, String expected) throws IOException {
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(hash(expected)), HexFormat.of().parseHex(sha256(bytes)))) {
            throw new IOException("SHA-256 verification failed");
        }
    }
}
