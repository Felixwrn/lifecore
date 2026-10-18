package de.felix.lifeplugin.gui;

import java.util.UUID;
import org.bukkit.event.inventory.ClickType;

/** The same boundary is applied to every menu, including confirmation menus. */
final class MenuAccess {
    private MenuAccess() { }

    static boolean permits(UUID owner, UUID actor, boolean permission, boolean ready,
                           int rawSlot, int topSize, boolean clickedTop, ClickType click) {
        return owner.equals(actor) && permission && ready && clickedTop
                && rawSlot >= 0 && rawSlot < topSize
                && (click == ClickType.LEFT || click == ClickType.RIGHT);
    }
}
