package de.felix.lifeplugin.lang;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.felix.lifeplugin.Main;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.UUID;

public class LanguageManager {

    private final HashMap<String, HashMap<String, String>> languages = new HashMap<>();
    private final Gson gson = new Gson();

    // 📥 Load JSON files
    public void load(File folder) {

        if (!folder.exists()) folder.mkdirs();

        File[] files = folder.listFiles();
        if (files == null) return;

        Type type = new TypeToken<HashMap<String, String>>() {}.getType();

        for (File file : files) {

            if (!file.getName().endsWith(".json")) continue;

            try (FileReader reader = new FileReader(file)) {

                String name = file.getName().replace(".json", "");

                HashMap<String, String> map = gson.fromJson(reader, type);

                if (map == null) {
                    map = new HashMap<>();
                }

                languages.put(name, map);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // 🌍 Get language (JETZT AUS CONFIG!)
    public String getLanguage(UUID uuid) {

        return Main.getInstance()
                .getConfig()
                .getString("player-lang." + uuid, "de");
    }

    // 📤 Get message
    public String get(UUID uuid, String key) {

        String lang = getLanguage(uuid);

        HashMap<String, String> map = languages.get(lang);

        // fallback to German
        if (map == null) {
            map = languages.get("de");
        }

        if (map == null) {
            return "§c[Missing Lang System]";
        }

        return map.getOrDefault(key, "§c[Missing: " + key + "]");
    }

    // 🔧 Placeholder support
    public String format(UUID uuid, String key, String... placeholders) {

        String msg = get(uuid, key);

        for (int i = 0; i < placeholders.length - 1; i += 2) {
            msg = msg.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }

        return msg;
    }
}
