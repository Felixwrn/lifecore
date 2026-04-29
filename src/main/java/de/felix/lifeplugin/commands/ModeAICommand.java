
/* package de.felix.lifeplugin.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.felix.lifeplugin.Main;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class ModeAICommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) return true;

        if (args.length >= 2 && args[0].equalsIgnoreCase("ai")) {

            String input = String.join(" ", args).substring(3);

            p.sendMessage("§7AI is creating your mode...");

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {

                String prompt = buildPrompt(input);
                String response = Main.getInstance().getAI().ask(prompt);

                if (response == null) {
                    p.sendMessage("§cAI failed!");
                    return;
                }

                JsonObject json;

                try {
                    json = JsonParser.parseString(response).getAsJsonObject();
                } catch (Exception e) {
                    p.sendMessage("§cInvalid AI response!");
                    return;
                }

                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {

                    String name = json.has("name") ? json.get("name").getAsString() : "custom";
                    int lives = json.has("lives") ? json.get("lives").getAsInt() : 10;

                    p.sendMessage("§a✔ Mode created: §e" + name);
                    p.sendMessage("§7Lives: §a" + lives);
                });
            });

            return true;
        }

        p.sendMessage("§cUsage: /mode ai <description>");
        return true;
    }

    private String buildPrompt(String input) {
        return "Convert this into JSON with fields name and lives: " + input;
    }
}
*/
