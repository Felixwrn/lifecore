package de.felix.lifeplugin.commands;

import de.felix.lifeplguin.lang.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LanguageCommand {

    private final LanguageManager lang;

    public LanguageCommand(LanguageManager lang) {
        this.lang = lang;
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) return true;

        if (args.length == 0) {
            p.sendMessage("§cUsage: /language <de|en>");
            return true;
        }

        String langCode = args[0].toLowerCase();

        if (!langCode.equals("de") && !langCode.equals("en")) {
            p.sendMessage("§cUnknown language!");
            return true;
        }

        lang.setLanguage(p.getUniqueId(), langCode);

        p.sendMessage("§aLanguage set to: " + langCode);

        return true;
    }
}
