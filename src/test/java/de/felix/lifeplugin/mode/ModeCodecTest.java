package de.felix.lifeplugin.mode;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModeCodecTest {
    private static ModeDefinition sample() {
        return new ModeDefinition("example", "Example", "A safe mode", "DIAMOND", 3, 10, true, true, 1, false);
    }

    @Test
    void roundTripsAllFieldsWithoutCoercion() {
        var mode = sample();
        JsonObject encoded = ModeCodec.toJson(mode);
        assertEquals(ModeCodec.FIELDS, encoded.keySet());
        assertEquals(mode, ModeCodec.fromJson(ModeCodec.parseJson(encoded.toString())));
        assertTrue(encoded.get("starting-lives").getAsJsonPrimitive().isNumber());
        assertTrue(encoded.get("ban-on-zero").getAsJsonPrimitive().isBoolean());
    }

    @Test
    void rejectsUnknownMissingAndExecutableOptions() {
        JsonObject missing = ModeCodec.toJson(sample());
        missing.remove("name");
        assertThrows(IllegalArgumentException.class, () -> ModeCodec.fromJson(missing));
        JsonObject executable = ModeCodec.toJson(sample());
        executable.addProperty("commands", "op attacker");
        assertThrows(IllegalArgumentException.class, () -> ModeCodec.fromJson(executable));
        assertThrows(IllegalArgumentException.class, () -> ModeCodec.fromJson(null));
    }

    @Test
    void rejectsPrimitiveTypeCoercionNullAndOutOfRangeNumbers() {
        for (String field : new String[]{"starting-lives", "max-lives", "steal-amount"}) {
            JsonObject object = ModeCodec.toJson(sample());
            object.addProperty(field, "3");
            assertThrows(IllegalArgumentException.class, () -> ModeCodec.fromJson(object));
            object.addProperty(field, 1.5);
            assertThrows(IllegalArgumentException.class, () -> ModeCodec.fromJson(object));
            object.addProperty(field, 9_223_372_036_854_775_807L);
            assertThrows(IllegalArgumentException.class, () -> ModeCodec.fromJson(object));
        }
        JsonObject object = ModeCodec.toJson(sample());
        object.addProperty("ban-on-zero", "false");
        assertThrows(IllegalArgumentException.class, () -> ModeCodec.fromJson(object));
        object.add("ban-on-zero", JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> ModeCodec.fromJson(object));
        JsonObject numberName = ModeCodec.toJson(sample());
        numberName.addProperty("name", 42);
        assertThrows(IllegalArgumentException.class, () -> ModeCodec.fromJson(numberName));
    }

    @Test
    void strictJsonRejectsDuplicateKeysCommentsTrailingValuesAndOversizedDocuments() {
        for (String input : new String[]{"{\"x\":1,\"x\":2}", "{/*comment*/\"x\":1}", "{\"x\":1} {}",
                "{x:1}", "{\"x\":NaN}", "[]", "{\"x\":1,}", "{\"x\":\"unterminated}"}) {
            assertThrows(IllegalArgumentException.class, () -> ModeCodec.parseJson(input), input);
        }
        assertThrows(IllegalArgumentException.class, () -> ModeCodec.parseJson(" ".repeat(262_145)));
        assertThrows(IllegalArgumentException.class, () -> ModeCodec.parseJson("{\"x\":" + "[".repeat(18)
                + "0" + "]".repeat(18) + "}"));
    }

    @Test
    void legacyFieldsNeverBecomeAnOnlineSchema() {
        assertThrows(IllegalArgumentException.class, () -> ModeCodec.fromJson(ModeCodec.parseJson("""
                {"steal-on-kill":true,"steal-amount":1,"max-lives":10,"icon":"DIAMOND","description":"Old"}
                """)));
    }
}
