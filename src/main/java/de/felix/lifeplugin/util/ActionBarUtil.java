package de.felix.lifeplugin.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

public class ActionBarUtil {

    public static void send(Player p, String msg) {
        p.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                new TextComponent(msg)
        );
    }
}
