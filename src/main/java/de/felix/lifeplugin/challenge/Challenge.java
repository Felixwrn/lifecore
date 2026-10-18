package de.felix.lifeplugin.challenge;

import org.bukkit.Material;
import org.bukkit.block.BlockType;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemType;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** A bounded, declarative challenge. Rewards are experience points, never commands. */
public final class Challenge {
    public static final int MAX_GOAL = 1_000_000;
    public static final int MAX_REWARD = 100_000;
    // Read API field metadata without initializing the live server registries.
    // Definitions are also parsed by offline tooling and asynchronous downloads.
    private static final Set<String> BLOCK_TARGETS = targets(BlockType.class);
    private static final Set<String> ITEM_TARGETS = targets(ItemType.class);
    private final String id;
    private final String name;
    private final String type;
    private final String target;
    private final int goal;
    private final int reward;

    /** Compatibility with the original stone-mining challenge format. */
    public Challenge(String id, String name, String type, int goal, int reward) {
        this(id, name, type, "Break Stone".equals(type) ? "STONE" : null, goal, reward);
    }

    public Challenge(String id, String name, String type, String target, int goal, int reward) {
        this.id = validateId(id);
        if (name == null || name.isBlank() || name.length() > 80
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Challenge name must contain 1-80 printable characters");
        }
        this.name = name;
        this.type = "Break Stone".equals(type) ? "BREAK_BLOCK" : type;
        this.target = "Break Stone".equals(type) && target == null ? "STONE" : target;
        if (this.target == null || !this.target.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Challenge target must be a Minecraft enum name");
        }
        if (this.type == null) throw new IllegalArgumentException("Challenge event is required");
        switch (this.type) {
            case "BREAK_BLOCK", "CRAFT_ITEM" -> {
                Material material;
                try { material = Material.valueOf(this.target); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown challenge material"); }
                Set<String> targets = this.type.equals("BREAK_BLOCK") ? BLOCK_TARGETS : ITEM_TARGETS;
                if (material.name().startsWith("LEGACY_") || !targets.contains(this.target)) {
                    throw new IllegalArgumentException("Challenge material is unusable");
                }
            }
            case "KILL_MOB" -> {
                EntityType entity;
                try { entity = EntityType.valueOf(this.target); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown challenge entity"); }
                if (!entity.isAlive() || entity == EntityType.PLAYER) {
                    throw new IllegalArgumentException("Challenge target must be a living mob");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported challenge event");
        }
        if (goal < 1 || goal > MAX_GOAL || reward < 0 || reward > MAX_REWARD) {
            throw new IllegalArgumentException("Challenge goal or XP reward is outside its allowed range");
        }
        this.goal = goal;
        this.reward = reward;
    }

    private static Set<String> targets(Class<?> apiType) {
        return Arrays.stream(apiType.getFields())
                .filter(field -> apiType.isAssignableFrom(field.getType()))
                .map(java.lang.reflect.Field::getName)
                .filter(name -> !name.equals("AIR") && !name.equals("CAVE_AIR") && !name.equals("VOID_AIR"))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static String validateId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,47}")) {
            throw new IllegalArgumentException("Challenge id must contain 1-48 lowercase letters, digits, underscores or hyphens");
        }
        return id;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getTarget() { return target; }
    public int getGoal() { return goal; }
    public int getReward() { return reward; }
}
