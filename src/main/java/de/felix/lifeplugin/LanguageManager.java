package de.felix.lifeplugin.lang;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.UUID;

public class LanguageManager {

    private final HashMap<String, HashMap<String, String>> languages = new HashMap<>();
    private final HashMap<UUID, String> playerLang = new HashMap<>();

    private final Gson gson = new Gson();

    // 📥 Load JSON files
    public void load(File folder) {

        if (!folder.exists()) folder.mkdirs();

        File[] files = folder.listFiles();

        if (files == null) return;

        Type type = new TypeToken<HashMap<String, String>>() {}.getType();

        for (File file : files) {

            if (!file.getName().endsWith(".json")) continue;

            try {

                String name = file.getName().replace(".json", "");

                FileReader reader = new FileReader(file);

                HashMap<String, String> map = gson.fromJson(reader, type);

                languages.put(name, map);

                reader.close();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // 🌍 Set language
    public void setLanguage(UUID uuid, String lang) {
        playerLang.put(uuid, lang);
    }

    // 🌍 Get language
    public String getLanguage(UUID uuid) {
        return playerLang.getOrDefault(uuid, "de");
    }

    // 📤 Get message
    public String get(UUID uuid, String key) {

        String lang = getLanguage(uuid);

        HashMap<String, String> map = languages.get(lang);

        if (map == null) return key;

        return map.getOrDefault(key, key);
    }

    // 🔧 Placeholder support
    public String format(UUID uuid, String key, String... placeholders) {

        String msg = get(uuid, key);

        for (int i = 0; i < placeholders.length; i += 2) {
            msg = msg.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }

        return msg;
    }
}
