package de.felix.lifeplugin.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.UUID;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

class MenuAccessTest {
    private final UUID owner = UUID.randomUUID();

    @Test void onlyOwnerWithCurrentPermissionAndReadyDataCanUseMenu() {
        assertTrue(MenuAccess.permits(owner, owner, true, true, 11, 27, true, ClickType.LEFT));
        assertTrue(MenuAccess.permits(owner, owner, true, true, 11, 27, true, ClickType.RIGHT));
        assertFalse(MenuAccess.permits(owner, UUID.randomUUID(), true, true, 11, 27, true, ClickType.LEFT));
        assertFalse(MenuAccess.permits(owner, owner, false, true, 11, 27, true, ClickType.LEFT));
        assertFalse(MenuAccess.permits(owner, owner, true, false, 11, 27, true, ClickType.LEFT));
    }

    @Test void bottomInventoryAndOutsideSlotsNeverBecomeMenuActions() {
        assertFalse(MenuAccess.permits(owner, owner, true, true, 11, 27, false, ClickType.LEFT));
        assertFalse(MenuAccess.permits(owner, owner, true, true, 27, 27, true, ClickType.LEFT));
        assertFalse(MenuAccess.permits(owner, owner, true, true, 65, 27, false, ClickType.LEFT));
        assertFalse(MenuAccess.permits(owner, owner, true, true, -999, 27, false, ClickType.LEFT));
    }

    @Test void shiftHotbarOffhandAndDoubleClicksCannotTriggerActions() {
        for (ClickType click : ClickType.values()) {
            if (click == ClickType.LEFT || click == ClickType.RIGHT) continue;
            assertFalse(MenuAccess.permits(owner, owner, true, true, 11, 27, true, click), click.name());
        }
    }
}
