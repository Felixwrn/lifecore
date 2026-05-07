package de.felix.lifeplugin.challenge;

import de.felix.lifeplugin.Main;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class GitHubChallengeDownloader {

    // CHANGE THIS
    private static final String BASE_URL =
            "https://raw.githubusercontent.com/WRNlabs/challenges/main/";

    // CHALLENGES
    private static final String[] FILES = {
            "stone_miner.yml"
    };

    public static void downloadChallenges() {

        try {

            File folder = new File(
                    Main.getInstance().getDataFolder(),
                    "challenges"
            );

            if (!folder.exists()) {
                folder.mkdirs();
            }

            for (String fileName : FILES) {

                URL url = new URL(BASE_URL + fileName);

                InputStream in = url.openStream();

                File output = new File(folder, fileName);

                Files.copy(
                        in,
                        output.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );

                in.close();

                Main.getInstance().getLogger().info(
                        "Downloaded challenge: " + fileName
                );
            }

        } catch (Exception e) {

            Main.getInstance().getLogger().warning(
                    "Failed to download challenges!"
            );

            e.printStackTrace();
        }
    }
}
