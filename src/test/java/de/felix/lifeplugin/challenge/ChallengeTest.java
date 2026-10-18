package de.felix.lifeplugin.challenge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ChallengeTest {
    private static final String VALID = "id: stone_miner\nname: Stone Miner\ntype: BREAK_BLOCK\ntarget: STONE\ngoal: 5\nreward: 12\n";

    @Test void supportsAllThreeDeclarativeEventsAndRoundTrips() {
        for (Challenge challenge : new Challenge[] {
                new Challenge("stone", "Stone", "BREAK_BLOCK", "STONE", 5, 12),
                new Challenge("zombie", "Zombie", "KILL_MOB", "ZOMBIE", 2, 30),
                new Challenge("bread", "Bread", "CRAFT_ITEM", "BREAD", 6, 0)}) {
            Challenge restored = ChallengeManager.parse(ChallengeManager.serialize(challenge));
            assertEquals(challenge.getId(), restored.getId());
            assertEquals(challenge.getName(), restored.getName());
            assertEquals(challenge.getType(), restored.getType());
            assertEquals(challenge.getTarget(), restored.getTarget());
            assertEquals(challenge.getGoal(), restored.getGoal());
            assertEquals(challenge.getReward(), restored.getReward());
        }
    }

    @Test void importsLegacyStoneChallengeWithoutTarget() {
        Challenge challenge = ChallengeManager.parse(VALID.replace("BREAK_BLOCK", "Break Stone").replace("target: STONE\n", ""));
        assertEquals("BREAK_BLOCK", challenge.getType());
        assertEquals("STONE", challenge.getTarget());
        assertEquals("STONE", new Challenge("legacy", "Legacy", "Break Stone", 1, 1).getTarget());
    }

    @ParameterizedTest @ValueSource(strings = {
            "id: ../outside", "id: Uppercase", "id: ''", "name: ''", "name: 123",
            "type: COMMAND", "type: break_block", "target: nonexistent", "target: AIR",
            "goal: 0", "goal: 1000001", "goal: '5'", "goal: 1.5", "goal: true",
            "reward: -1", "reward: 100001", "reward: 1.2", "reward: null"})
    void rejectsInvalidDefinitions(String replacement) {
        String key = replacement.substring(0, replacement.indexOf(':'));
        String text = VALID.replaceAll("(?m)^" + key + ":.*$", replacement);
        assertThrows(IllegalArgumentException.class, () -> ChallengeManager.parse(text));
    }

    @Test void rejectsUnknownFieldsDuplicatesTagsAndMultipleDocuments() {
        for (String text : new String[] { VALID + "command: op player\n", VALID + "goal: 4\n",
                "!!java.lang.ProcessBuilder {}", VALID + "---\n" + VALID, VALID + "extra: &x [*x]\n",
                "[id, name]", VALID.replace("target: STONE\n", ""), "x".repeat(16_385)}) {
            assertThrows(IllegalArgumentException.class, () -> ChallengeManager.parse(text));
        }
    }

    @Test void rejectsPlayersAndNonlivingMobTargets() {
        assertThrows(IllegalArgumentException.class, () -> new Challenge("x", "X", "KILL_MOB", "PLAYER", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Challenge("x", "X", "KILL_MOB", "ITEM", 1, 1));
    }

    @Test void rejectsWrongMaterialCategoryAndAllAirVariantsWithoutAServer() {
        assertThrows(IllegalArgumentException.class, () -> new Challenge("x", "X", "BREAK_BLOCK", "DIAMOND", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Challenge("x", "X", "CRAFT_ITEM", "WATER", 1, 1));
        for (String air : new String[] {"AIR", "CAVE_AIR", "VOID_AIR", "LEGACY_STONE"}) {
            assertThrows(IllegalArgumentException.class, () -> new Challenge("x", "X", "BREAK_BLOCK", air, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new Challenge("x", "X", "CRAFT_ITEM", air, 1, 1));
        }
    }
}
