package de.felix.lifeplugin.util;

import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.*;
import java.util.function.Consumer;

public class ChatInput implements Listener {

    private static final Map<UUID, Consumer<String>> map = new HashMap<>();

    public static void wait(Player p, Consumer<String> c){
        map.put(p.getUniqueId(), c);
    }

    @EventHandler
    public void chat(AsyncPlayerChatEvent e){
        UUID id = e.getPlayer().getUniqueId();
        if(!map.containsKey(id)) return;

        e.setCancelled(true);
        map.remove(id).accept(e.getMessage());
    }
}
