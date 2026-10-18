package de.felix.lifeplugin.gui;

import de.felix.lifeplugin.Main;
import de.felix.lifeplugin.market.MarketplaceManager;
import de.felix.lifeplugin.mode.ModeDefinition;
import de.felix.lifeplugin.util.ChatInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Menus are identified by a private holder and UUID, never by a title or item text. */
public final class LifeCoreMenus implements Listener {
    private static final int PAGE_SIZE = 45;
    private final Main plugin;

    public LifeCoreMenus(Main plugin) { this.plugin = plugin; }

    public void openModes(Player player) { openModes(player, 0); }

    private void openModes(Player player, int requestedPage) {
        if (!allowed(player, "life.mode", false)) return;
        List<ModeDefinition> modes = plugin.getModes().all();
        int page = page(requestedPage, modes.size());
        Menu menu = menu(player, "life.mode", false, 54, "§bLifeCore modes");
        for (int index = page * PAGE_SIZE; index < Math.min(modes.size(), (page + 1) * PAGE_SIZE); index++) {
            ModeDefinition mode = modes.get(index);
            put(menu, index % PAGE_SIZE, icon(mode.icon()), "§e" + plain(mode.name()), click -> {
                if (click == ClickType.RIGHT) openBuilder(player, mode, true);
                else {
                    try {
                        plugin.setActiveMode(mode.id());
                        player.sendMessage("§aActive mode: " + mode.id());
                        openModes(player, page);
                    } catch (IOException | IllegalArgumentException failure) { error(player, failure); }
                }
            }, "§7ID: " + mode.id(), "§7" + summary(mode.description()),
                    "§7Starting/max lives: " + mode.startingLives() + "/" + mode.maxLives(),
                    "§aLeft: activate §7| §eRight: edit");
        }
        navigation(menu, page, modes.size(), next -> openModes(player, next));
        put(menu, 46, Material.CRAFTING_TABLE, "§aCreate mode", click -> ask(player, "life.mode", false,
                "Mode ID (lowercase letters, numbers, _ and -)", id -> {
                    ModeDefinition.validateId(id);
                    if (plugin.getModes().find(id).isPresent()) throw new IllegalArgumentException("This mode ID already exists");
                    openBuilder(player, new ModeDefinition(id, id, "", "DIAMOND", 3, 20, true, true, 1, false), false);
                }));
        put(menu, 47, Material.NETHER_STAR, "§dAI mode preview", click -> {
            if (!allowed(player, "life.ai", false)) return;
            ask(player, "life.ai", false, "New mode ID", id -> {
                ModeDefinition.validateId(id);
                ask(player, "life.ai", false, "Describe your game mode", prompt -> plugin.requestAi(player, id, prompt));
            });
        }, "§7Requires life.ai", "§7Review the result before saving");
        put(menu, 49, Material.PAPER, "§bActive: " + plain(plugin.getServerMode()), null,
                "§7Page " + (page + 1) + "/" + pages(modes.size()));
        player.openInventory(menu.inventory);
    }

    public void openBuilder(Player player, ModeDefinition mode, boolean overwrite) {
        if (allowed(player, "life.mode", false)) openBuilder(player, new Draft(mode), overwrite);
    }

    private void openBuilder(Player player, Draft draft, boolean overwrite) {
        if (!allowed(player, "life.mode", false)) return;
        Menu menu = menu(player, "life.mode", false, 45, "§6Mode Builder: " + draft.id);
        put(menu, 4, icon(draft.icon), "§e" + plain(draft.name), null, "§7ID: " + draft.id,
                "§7Preview only; changes take effect after saving");
        put(menu, 10, Material.NAME_TAG, "§eName", click -> ask(player, "life.mode", false, "Display name", value -> {
            draft.replaceText(value, draft.description, draft.icon);
            openBuilder(player, draft, overwrite);
        }), "§7" + plain(draft.name));
        put(menu, 11, Material.WRITABLE_BOOK, "§eDescription", click -> ask(player, "life.mode", false, "Mode description", value -> {
            draft.replaceText(draft.name, value, draft.icon);
            openBuilder(player, draft, overwrite);
        }), "§7" + summary(draft.description));
        put(menu, 12, icon(draft.icon), "§eIcon", click -> ask(player, "life.mode", false, "Material name, e.g. DIAMOND", value -> {
            draft.replaceText(draft.name, draft.description, value.toUpperCase(Locale.ROOT));
            openBuilder(player, draft, overwrite);
        }), "§7" + draft.icon);
        put(menu, 19, Material.APPLE, "§cStarting lives: " + draft.starting, click -> {
            draft.starting = clamp(draft.starting + delta(click), 1, draft.maximum);
            openBuilder(player, draft, overwrite);
        }, "§7Left +1 / Right -1", "§7Range: 1 to max lives");
        put(menu, 20, Material.GOLDEN_APPLE, "§cMax lives: " + draft.maximum, click -> {
            draft.maximum = clamp(draft.maximum + delta(click), 1, ModeDefinition.MAX_LIVES);
            draft.starting = Math.min(draft.starting, draft.maximum);
            draft.amount = Math.min(draft.amount, draft.maximum);
            openBuilder(player, draft, overwrite);
        }, "§7Left +1 / Right -1", "§7Range: 1 to " + ModeDefinition.MAX_LIVES);
        put(menu, 21, Material.REDSTONE, "§cSteal amount: " + draft.amount, click -> {
            draft.amount = clamp(draft.amount + delta(click), 0, draft.maximum);
            openBuilder(player, draft, overwrite);
        }, "§7Left +1 / Right -1", "§7Range: 0 to max lives");
        put(menu, 23, toggle(draft.lose), "§eLose lives on death: " + draft.lose, click -> {
            draft.lose = !draft.lose; openBuilder(player, draft, overwrite);
        });
        put(menu, 24, toggle(draft.steal), "§eSteal on kill: " + draft.steal, click -> {
            draft.steal = !draft.steal; openBuilder(player, draft, overwrite);
        });
        put(menu, 25, toggle(draft.ban), "§eBan on zero lives: " + draft.ban, click -> {
            draft.ban = !draft.ban; openBuilder(player, draft, overwrite);
        });
        put(menu, 37, Material.ARROW, "§7Back; discard preview", click -> openModes(player));
        put(menu, 39, Material.EMERALD, "§aSAVE", click -> {
            player.closeInventory(); plugin.saveMode(player, draft.build(), overwrite, false);
        }, overwrite ? "§eReplaces this existing mode" : "§7Creates a new mode", "§7Keeps the current active mode");
        put(menu, 41, Material.EMERALD_BLOCK, "§aSAVE + ACTIVATE", click -> {
            player.closeInventory(); plugin.saveMode(player, draft.build(), overwrite, true);
        }, overwrite ? "§eReplaces this existing mode" : "§7Creates a new mode", "§7Sets this as the server mode");
        player.openInventory(menu.inventory);
    }

    public void openMarket(Player player) { openMarket(player, 0); }

    private void openMarket(Player player, int requestedPage) {
        if (!allowed(player, "life.market", false)) return;
        if (plugin.getMarketplace() == null) { player.sendMessage("§eMarketplace is not configured."); return; }
        List<MarketplaceManager.CatalogEntry> entries = plugin.getMarketplace().entries();
        int page = page(requestedPage, entries.size());
        Menu menu = menu(player, "life.market", false, 54, "§5LifeCore Marketplace");
        for (int index = page * PAGE_SIZE; index < Math.min(entries.size(), (page + 1) * PAGE_SIZE); index++) {
            MarketplaceManager.CatalogEntry entry = entries.get(index);
            put(menu, index % PAGE_SIZE, entry.type().equals("pack") ? Material.CHEST : Material.PAPER,
                    "§d" + plain(entry.name()), click -> {
                        if (click == ClickType.RIGHT) confirmReplace(player, entry);
                        else { player.closeInventory(); plugin.installMarket(player, entry.id(), false); }
                    }, "§7" + summary(entry.description()), "§7" + entry.type() + " / " + plain(entry.version()),
                    "§aLeft: install §7| §eRight: replace existing data");
        }
        navigation(menu, page, entries.size(), next -> openMarket(player, next));
        put(menu, 49, Material.CLOCK, "§aRefresh catalog", click -> plugin.refreshMarket(player),
                entries.isEmpty() ? "§7No cached entries; refresh to load" : "§7Cached entries: " + entries.size());
        player.openInventory(menu.inventory);
    }

    private void confirmReplace(Player player, MarketplaceManager.CatalogEntry entry) {
        if (!allowed(player, "life.market", false)) return;
        Menu menu = menu(player, "life.market", false, 27, "§cConfirm marketplace replacement");
        put(menu, 11, Material.RED_WOOL, "§cReplace local data", click -> {
            player.closeInventory(); plugin.installMarket(player, entry.id(), true);
        }, "§7Entry: " + entry.id(), "§eExisting modes and challenges in this pack will be replaced");
        put(menu, 15, Material.GRAY_WOOL, "§7Cancel", click -> openMarket(player));
        player.openInventory(menu.inventory);
    }

    public void openLives(Player player) { openLives(player, 0); }

    private void openLives(Player player, int requestedPage) {
        if (!allowed(player, "life.admin", false)) return;
        List<Player> targets = new ArrayList<>(Bukkit.getOnlinePlayers());
        targets.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(Player::getUniqueId));
        int page = page(requestedPage, targets.size());
        Menu menu = menu(player, "life.admin", false, 54, "§cLifeCore lives");
        for (int index = page * PAGE_SIZE; index < Math.min(targets.size(), (page + 1) * PAGE_SIZE); index++) {
            Player target = targets.get(index);
            UUID targetId = target.getUniqueId();
            boolean loaded = plugin.getDataService().isLoaded(targetId);
            put(menu, index % PAGE_SIZE, Material.PLAYER_HEAD, "§e" + plain(target.getName()),
                    click -> openEditLives(player, targetId),
                    loaded ? "§7Lives: " + plugin.getDataService().get(targetId).lives() : "§7Player data is loading",
                    "§8" + targetId);
        }
        navigation(menu, page, targets.size(), next -> openLives(player, next));
        put(menu, 49, Material.CLOCK, "§eRefresh players", click -> openLives(player, page),
                "§7Page " + (page + 1) + "/" + pages(targets.size()));
        player.openInventory(menu.inventory);
    }

    public void openEditLives(Player player, UUID targetId) {
        if (!allowed(player, "life.admin", false)) return;
        if (!plugin.getDataService().isLoaded(targetId)) { player.sendMessage("§ePlayer data is not loaded yet."); return; }
        Player target = Bukkit.getPlayer(targetId);
        String label = target == null ? targetId.toString() : target.getName();
        Menu menu = menu(player, "life.admin", false, 27, "§cEdit lives");
        put(menu, 13, Material.PLAYER_HEAD, "§e" + plain(label), null,
                "§7Lives: " + plugin.getDataService().get(targetId).lives(), "§8" + targetId);
        put(menu, 11, Material.LIME_WOOL, "§a+1 life", click -> changeLives(player, targetId, 1));
        put(menu, 15, Material.RED_WOOL, "§c-1 life", click -> changeLives(player, targetId, -1));
        put(menu, 22, Material.ARROW, "§7Back", click -> openLives(player));
        player.openInventory(menu.inventory);
    }

    private void changeLives(Player player, UUID targetId, int change) {
        if (!plugin.getDataService().isLoaded(targetId)) { player.sendMessage("§ePlayer data is not loaded."); return; }
        int result = plugin.changeLives(targetId, change);
        player.sendMessage("§aLives updated: " + result);
        openEditLives(player, targetId);
    }

    public void openLanguages(Player player) {
        if (!allowed(player, "life.language", true)) return;
        Menu menu = menu(player, "life.language", true, 9, "§6LifeCore language");
        put(menu, 3, Material.PAPER, "§eDeutsch", click -> setLanguage(player, "de"));
        put(menu, 5, Material.BOOK, "§eEnglish", click -> setLanguage(player, "en"));
        player.openInventory(menu.inventory);
    }

    private void setLanguage(Player player, String language) {
        plugin.setLanguage(player.getUniqueId(), language);
        player.sendMessage("§aLanguage: " + language);
        player.closeInventory();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) return;
        // Cancel the entire view, including bottom inventory, shift, keyboard and outside clicks.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean permitted = player.hasPermission(menu.permission);
        boolean ready = plugin.isReady() && (!menu.dataRequired || plugin.getDataService().isLoaded(player.getUniqueId()));
        if (!MenuAccess.permits(menu.owner, player.getUniqueId(), permitted, ready, event.getRawSlot(),
                top.getSize(), event.getClickedInventory() == top, event.getClick())) return;
        Consumer<ClickType> action = menu.actions.get(event.getRawSlot());
        if (action != null) {
            ClickType click = event.getClick();
            // Bukkit requires inventory opening/closing to run after this event finishes.
            // A stale queued click must not act on a menu which was closed or replaced.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory() != top
                        || !allowed(player, menu.permission, menu.dataRequired)) return;
                try { action.accept(click); }
                catch (IllegalArgumentException | IllegalStateException failure) { error(player, failure); }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    private boolean allowed(Player player, String permission, boolean needsData) {
        if (!player.hasPermission(permission)) { player.sendMessage("§cMissing permission: " + permission); return false; }
        if (!plugin.isReady()) { player.sendMessage("§eLifeCore is still starting."); return false; }
        if (needsData && !plugin.getDataService().isLoaded(player.getUniqueId())) {
            player.sendMessage("§eYour player data is still loading."); return false;
        }
        return true;
    }

    private void ask(Player player, String permission, boolean needsData, String prompt, Consumer<String> callback) {
        if (!allowed(player, permission, needsData)) return;
        player.closeInventory();
        player.sendMessage("§e" + prompt + " §7(Type cancel to cancel; expires after 60 seconds.)");
        ChatInput.wait(player, value -> {
            if (!allowed(player, permission, needsData)) return;
            try { callback.accept(value); }
            catch (IllegalArgumentException | IllegalStateException failure) { error(player, failure); }
        });
    }

    private static Menu menu(Player player, String permission, boolean needsData, int size, String title) {
        Menu menu = new Menu(player.getUniqueId(), permission, needsData);
        menu.inventory = Bukkit.createInventory(menu, size, title);
        return menu;
    }

    private static void put(Menu menu, int slot, Material material, String name, Consumer<ClickType> action, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); meta.setLore(List.of(lore)); stack.setItemMeta(meta); }
        menu.inventory.setItem(slot, stack);
        if (action != null) menu.actions.put(slot, action);
    }

    private static void navigation(Menu menu, int page, int size, Consumer<Integer> callback) {
        if (page > 0) put(menu, 45, Material.ARROW, "§ePrevious page", click -> callback.accept(page - 1));
        if (page + 1 < pages(size)) put(menu, 53, Material.ARROW, "§eNext page", click -> callback.accept(page + 1));
    }

    private static int pages(int size) { return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE); }
    private static int page(int requested, int size) { return clamp(requested, 0, pages(size) - 1); }
    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static int delta(ClickType click) { return click == ClickType.RIGHT ? -1 : 1; }
    private static Material toggle(boolean value) { return value ? Material.LIME_DYE : Material.GRAY_DYE; }
    private static Material icon(String value) {
        Material material = Material.matchMaterial(value);
        return material == null || !material.isItem() || material.isAir() ? Material.PAPER : material;
    }
    private static String plain(String text) { return text.replace('§', '?').replace('\n', ' '); }
    private static String summary(String text) {
        String clean = plain(text);
        return clean.length() <= 90 ? clean : clean.substring(0, 87) + "...";
    }
    private static void error(Player player, Exception failure) { player.sendMessage("§c" + failure.getMessage()); }

    private static final class Menu implements InventoryHolder {
        private final UUID owner;
        private final String permission;
        private final boolean dataRequired;
        private final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
        private Inventory inventory;
        private Menu(UUID owner, String permission, boolean dataRequired) {
            this.owner = owner; this.permission = permission; this.dataRequired = dataRequired;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class Draft {
        private final String id;
        private String name, description, icon;
        private int starting, maximum, amount;
        private boolean lose, steal, ban;
        private Draft(ModeDefinition mode) {
            id = mode.id(); name = mode.name(); description = mode.description(); icon = mode.icon();
            starting = mode.startingLives(); maximum = mode.maxLives(); amount = mode.stealAmount();
            lose = mode.loseOnDeath(); steal = mode.stealOnKill(); ban = mode.banOnZero();
        }
        private ModeDefinition build() {
            return new ModeDefinition(id, name, description, icon, starting, maximum, lose, steal, amount, ban);
        }
        private void replaceText(String nextName, String nextDescription, String nextIcon) {
            new ModeDefinition(id, nextName, nextDescription, nextIcon, starting, maximum, lose, steal, amount, ban);
            name = nextName; description = nextDescription; icon = nextIcon;
        }
    }
}
