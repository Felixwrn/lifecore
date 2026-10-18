package de.felix.lifeplugin.mode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LifeRulesTest {
    @Test void gainsAreCappedAndLossesNeverBecomeNegative() {
        assertEquals(10, LifeRules.adjust(9, 5, 10));
        assertEquals(0, LifeRules.adjust(1, -5, 10));
        assertEquals(8, LifeRules.adjust(9, -1, 10));
    }
    @Test void loweringModeCapNeverDestroysExistingBalances() {
        assertEquals(20, LifeRules.adjust(20, 1, 10));
        assertEquals(19, LifeRules.adjust(20, -1, 10));
        assertEquals(Integer.MAX_VALUE, LifeRules.adjust(Integer.MAX_VALUE, 1, 10));
    }
    @Test void deathHonorsModeAndZeroBoundary() {
        var losing = new ModeDefinition("test", "Test", "", "PAPER", 3, 10, true, false, 0, false);
        var retaining = new ModeDefinition("test", "Test", "", "PAPER", 3, 10, false, false, 0, false);
        assertEquals(2, LifeRules.afterDeath(3, losing));
        assertEquals(0, LifeRules.afterDeath(0, losing));
        assertEquals(3, LifeRules.afterDeath(3, retaining));
    }
}
