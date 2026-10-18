package de.felix.lifeplugin.mode;

import org.bukkit.Material;
import org.bukkit.inventory.ItemType;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** The shared, declarative rules used by the GUI, AI and marketplace. */
public record ModeDefinition(String id, String name, String description, String icon,
                             int startingLives, int maxLives, boolean loseOnDeath,
                             boolean stealOnKill, int stealAmount, boolean banOnZero) {
    public static final int MAX_LIVES = 10_000;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 1_024;
    // Field names are API metadata: unlike Material.isItem()/isAir(), reflecting
    // them never initializes Bukkit's registry and is safe during offline parsing.
    private static final Set<String> ITEM_ICONS = Arrays.stream(ItemType.class.getFields())
            .filter(field -> ItemType.class.isAssignableFrom(field.getType()))
            .map(java.lang.reflect.Field::getName)
            .filter(name -> !name.equals("AIR"))
            .collect(Collectors.toUnmodifiableSet());

    public ModeDefinition {
        validateId(id);
        validateText(name, "name", 1, MAX_NAME_LENGTH, false);
        validateText(description, "description", 0, MAX_DESCRIPTION_LENGTH, true);
        if (icon == null || !icon.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid mode icon");
        }
        try {
            Material material = Material.valueOf(icon);
            if (material.name().startsWith("LEGACY_") || !ITEM_ICONS.contains(icon)) {
                throw new IllegalArgumentException("Invalid mode icon");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown or unusable mode icon");
        }
        if (startingLives < 1 || maxLives < 1 || startingLives > maxLives || maxLives > MAX_LIVES) {
            throw new IllegalArgumentException("Lives must be between 1 and 10000, with starting lives <= max lives");
        }
        if (stealAmount < 0 || stealAmount > maxLives) {
            throw new IllegalArgumentException("Steal amount must be between 0 and max lives");
        }
    }

    public static String validateId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,47}")) {
            throw new IllegalArgumentException("Mode id must contain 1-48 lowercase letters, digits, underscores or hyphens");
        }
        return id;
    }

    private static void validateText(String value, String field, int min, int max, boolean multiline) {
        if (value == null || value.length() < min || value.length() > max || (min > 0 && value.isBlank())) {
            throw new IllegalArgumentException("Invalid mode " + field + " length");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) && !(multiline && character == '\n')) {
                throw new IllegalArgumentException("Mode " + field + " contains control characters");
            }
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("Mode " + field + " contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Mode " + field + " contains invalid Unicode");
            }
        }
    }
}
