package de.felix.lifeplugin.mode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Set;

/** Strict data codec. Unknown fields (including executable options) are rejected. */
public final class ModeCodec {
    public static final Set<String> FIELDS = Set.of("id", "name", "description", "icon", "starting-lives",
            "max-lives", "lose-on-death", "steal-on-kill", "steal-amount", "ban-on-zero");

    private ModeCodec() { }

    public static ModeDefinition fromJson(JsonObject object) {
        if (object == null || !object.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("A mode must contain exactly the documented fields");
        }
        return new ModeDefinition(string(object, "id"), string(object, "name"), string(object, "description"),
                string(object, "icon"), integer(object, "starting-lives"), integer(object, "max-lives"),
                bool(object, "lose-on-death"), bool(object, "steal-on-kill"), integer(object, "steal-amount"),
                bool(object, "ban-on-zero"));
    }

    public static JsonObject toJson(ModeDefinition mode) {
        JsonObject object = new JsonObject();
        object.addProperty("id", mode.id());
        object.addProperty("name", mode.name());
        object.addProperty("description", mode.description());
        object.addProperty("icon", mode.icon());
        object.addProperty("starting-lives", mode.startingLives());
        object.addProperty("max-lives", mode.maxLives());
        object.addProperty("lose-on-death", mode.loseOnDeath());
        object.addProperty("steal-on-kill", mode.stealOnKill());
        object.addProperty("steal-amount", mode.stealAmount());
        object.addProperty("ban-on-zero", mode.banOnZero());
        return object;
    }

    /** Unlike Gson's lenient parser, rejects duplicate keys, comments and trailing input. */
    public static JsonObject parseJson(String input) {
        if (input == null || input.length() > 262_144) {
            throw new IllegalArgumentException("JSON document is missing or too large");
        }
        try (JsonReader reader = new JsonReader(new StringReader(input))) {
            reader.setLenient(false);
            JsonElement result = read(reader, 0);
            if (!result.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Expected one JSON object");
            }
            return result.getAsJsonObject();
        } catch (IOException | IllegalStateException | NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid JSON document");
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw new IllegalArgumentException("JSON document is nested too deeply");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw new IllegalArgumentException("Duplicate JSON field");
                    object.add(name, read(reader, depth + 1));
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
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("Invalid JSON value");
        };
    }

    private static String string(JsonObject object, String field) {
        JsonPrimitive primitive = primitive(object, field);
        if (!primitive.isString()) throw new IllegalArgumentException(field + " must be a string");
        return primitive.getAsString();
    }

    private static int integer(JsonObject object, String field) {
        JsonPrimitive primitive = primitive(object, field);
        if (!primitive.isNumber()) throw new IllegalArgumentException(field + " must be an integer");
        try {
            return primitive.getAsBigDecimal().intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer within range");
        }
    }

    private static boolean bool(JsonObject object, String field) {
        JsonPrimitive primitive = primitive(object, field);
        if (!primitive.isBoolean()) throw new IllegalArgumentException(field + " must be a boolean");
        return primitive.getAsBoolean();
    }

    private static JsonPrimitive primitive(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException(field + " has an invalid type");
        return value.getAsJsonPrimitive();
    }
}
