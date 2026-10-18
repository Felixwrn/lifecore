package de.felix.lifeplugin.mode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModeDefinitionTest {
    @Test
    void boundsAllowValidDeclarativeModes() {
        var mode = new ModeDefinition("survival_26-3", "Survival", "First line\nSecond line", "DIAMOND",
                3, 10, true, true, 1, false);
        assertEquals("survival_26-3", mode.id());
        assertDoesNotThrow(() -> new ModeDefinition("a", "A", "", "NETHER_STAR",
                1, ModeDefinition.MAX_LIVES, false, true, ModeDefinition.MAX_LIVES, true));
    }

    @Test
    void rejectsUnsafeIdentifiersAndIcons() {
        for (String id : new String[]{"", "../escape", "a/b", "a\\b", "Upper", ".hidden", "a".repeat(49)}) {
            assertThrows(IllegalArgumentException.class, () -> ModeDefinition.validateId(id), id);
        }
        assertThrows(IllegalArgumentException.class, () -> ModeDefinition.validateId(null));
        for (String icon : new String[]{"AIR", "CAVE_AIR", "WATER", "POWDER_SNOW", "LEGACY_DIAMOND", "NOT_A_MATERIAL", "diamond", "../DIAMOND"}) {
            assertThrows(IllegalArgumentException.class, () -> mode("A", "", icon, 3, 10, 1), icon);
        }
    }

    @Test
    void rejectsInvalidTextAndRules() {
        for (String name : new String[]{"", " ", "a".repeat(65), "line\nline", "bad\u0000", "bad\uD800"}) {
            assertThrows(IllegalArgumentException.class, () -> mode(name, "", "DIAMOND", 3, 10, 1));
        }
        for (String description : new String[]{"a".repeat(1_025), "bad\rvalue", "bad\u0000", "bad\uDC00"}) {
            assertThrows(IllegalArgumentException.class, () -> mode("A", description, "DIAMOND", 3, 10, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> mode("A", "", "DIAMOND", 0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> mode("A", "", "DIAMOND", 11, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> mode("A", "", "DIAMOND", 3, 10_001, 1));
        assertThrows(IllegalArgumentException.class, () -> mode("A", "", "DIAMOND", 3, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> mode("A", "", "DIAMOND", 3, 10, 11));
    }

    private static ModeDefinition mode(String name, String description, String icon, int starting, int max, int steal) {
        return new ModeDefinition("mode", name, description, icon, starting, max, true, true, steal, false);
    }
}
