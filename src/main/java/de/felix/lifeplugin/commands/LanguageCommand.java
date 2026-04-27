package de.felix.lifeplugin.commands;

import de.felix.lifeplugin.Main;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

public class LanguageCommand implements CommandExecutor, TabCompleter {

    private static final List<String> LANGS = Arrays.asList("de", "en", "fr", "es", "it");

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) return true;

        if (args.length == 0) {
            p.sendMessage("§cUsage: /language <" + String.join("|", LANGS) + ">");
            return true;
        }

        String lang = args[0].toLowerCase();

        // ❌ Ungültige Sprache
        if (!LANGS.contains(lang)) {
            p.sendMessage("§cUnknown language!");
            return true;
        }

        // ✅ Sprache setzen (NEUES SYSTEM)
        Main.getInstance().setLang(p, lang);

        p.sendMessage("§aLanguage set to: §e" + lang);

        return true;
    }

    // ---------------- TAB COMPLETION ----------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {

        if (args.length == 1) {
            return LANGS;
        }

        return List.of();
    }
}
