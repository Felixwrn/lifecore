package de.felix.lifeplugin.storage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** An immutable player snapshot; safe to pass between the server and storage threads. */
public record PlayerData(int lives, String language, Map<String, Integer> challengeProgress,
                         Set<String> completedChallenges) {
    public PlayerData {
        if (lives < 0) throw new IllegalArgumentException("Lives must be nonnegative");
        Objects.requireNonNull(language, "language");
        if (language.isBlank() || language.length() > 16) throw new IllegalArgumentException("Invalid language");
        challengeProgress = Map.copyOf(challengeProgress);
        completedChallenges = Set.copyOf(completedChallenges);
        challengeProgress.forEach((id, value) -> {
            validateId(id);
            if (value < 0) throw new IllegalArgumentException("Challenge progress must be nonnegative");
        });
        completedChallenges.forEach(PlayerData::validateId);
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Invalid challenge ID");
    }

    public PlayerData withLives(int value) { return new PlayerData(value, language, challengeProgress, completedChallenges); }
    public PlayerData withLanguage(String value) { return new PlayerData(lives, value, challengeProgress, completedChallenges); }
    public PlayerData withProgress(String id, int value) {
        Map<String, Integer> progress = new HashMap<>(challengeProgress);
        progress.put(id, value);
        return new PlayerData(lives, language, progress, completedChallenges);
    }
    public PlayerData completeChallenge(String id) {
        Set<String> completed = new HashSet<>(completedChallenges);
        completed.add(id);
        return new PlayerData(lives, language, challengeProgress, completed);
    }
}
