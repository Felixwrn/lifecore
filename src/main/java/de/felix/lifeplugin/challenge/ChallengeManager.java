package de.felix.lifeplugin.challenge;

import de.felix.lifeplugin.Main;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;

public class ChallengeManager {

    private static final HashMap<String, Challenge> challenges = new HashMap<>();

    public static void loadChallenges() {

        challenges.clear();

        File folder = new File(
                Main.getInstance().getDataFolder(),
                "challenges"
        );

        if (!folder.exists()) {
            folder.mkdirs();
        }

        File[] files = folder.listFiles();

        if (files == null) return;

        for (File file : files) {

            if (!file.getName().endsWith(".yml")) {
                continue;
            }

            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

            String id = cfg.getString("id");
            String name = cfg.getString("name");
            String type = cfg.getString("type");

            int goal = cfg.getInt("goal");
            int reward = cfg.getInt("reward");

            Challenge challenge = new Challenge(
                    id,
                    name,
                    type,
                    goal,
                    reward
            );

            challenges.put(id, challenge);

            Main.getInstance().getLogger().info(
                    "Loaded challenge: " + id
            );
        }
    }

    public static HashMap<String, Challenge> getChallenges() {
        return challenges;
    }
}
