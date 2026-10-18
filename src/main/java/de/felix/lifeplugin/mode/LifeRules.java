package de.felix.lifeplugin.mode;

/** Shared gameplay arithmetic; legacy balances above a new cap are never silently reduced. */
public final class LifeRules {
    private LifeRules() { }
    public static int adjust(int current, int delta, int maxLives) {
        if (current < 0 || maxLives < 1) throw new IllegalArgumentException("Invalid life balance or cap");
        long changed = Math.max(0L, Math.min(Integer.MAX_VALUE, (long) current + delta));
        return (int) (delta > 0 ? Math.min(changed, Math.max(current, maxLives)) : changed);
    }
    public static int afterDeath(int current, ModeDefinition mode) {
        if (current < 0) throw new IllegalArgumentException("Invalid life balance");
        return mode.loseOnDeath() ? Math.max(0, current - 1) : current;
    }
}
