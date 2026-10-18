package de.felix.lifeplugin;

import com.zaxxer.hikari.HikariConfig;
import de.felix.lifeplugin.ai.OpenAIService;
import de.felix.lifeplugin.challenge.*;
import de.felix.lifeplugin.gui.LifeCoreMenus;
import de.felix.lifeplugin.mode.*;
import de.felix.lifeplugin.market.MarketplaceManager;
import de.felix.lifeplugin.network.SafeHttpClient;
import de.felix.lifeplugin.storage.*;
import de.felix.lifeplugin.update.UpdateManager;
import de.felix.lifeplugin.util.ActionBarUtil;
import de.felix.lifeplugin.util.ChatInput;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Plugin lifecycle and Bukkit-thread adapter for the independently tested v2.1 services. */
public class Main extends JavaPlugin implements Listener, TabCompleter {
    private static Main instance;
    private final Object lifecycleLock = new Object();
    private volatile boolean ready;
    private volatile boolean stopping;
    private volatile PlayerDataService data;
    private volatile ChallengeManager challenges;
    private volatile MarketplaceManager marketplace;
    private volatile GitHubChallengeDownloader challengeDownloader;
    private volatile UpdateManager updater;
    private volatile ModeDefinition activeMode;
    private ModeRepository modes;
    private LifeCoreMenus menus;
    private OpenAIService ai;
    private ExecutorService workers;
    private boolean onlineEnabled;
    private boolean autoDownload;
    private URI challengeIndex;

    public static Main getInstance() { return instance; }
    public boolean isReady() { return ready && !stopping; }
    public PlayerDataService getDataService() { return data; }
    public ModeRepository getModes() { return modes; }
    public ModeDefinition getActiveMode() { return activeMode; }
    public LifeCoreMenus getMenus() { return menus; }
    public MarketplaceManager getMarketplace() { return marketplace; }
    public String getServerMode() { return activeMode == null ? "starting" : activeMode.id(); }
    public int getLives(Player player) { return data.get(player.getUniqueId()).lives(); }

    @Override public void onEnable() {
        instance = this;
        stopping = false;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        workers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "LifeCore-online"); thread.setDaemon(true); return thread;
        });
        try {
            modes = new ModeRepository(getDataFolder().toPath().resolve("modes"));
            modes.load();
            bootstrapModes();
            activeMode = modes.find(getConfig().getString("mode.current", "hardcore"))
                    .orElseThrow(() -> new IllegalArgumentException("Configured mode.current does not exist"));
            menus = new LifeCoreMenus(this);
            getServer().getPluginManager().registerEvents(menus, this);
            getServer().getPluginManager().registerEvents(new ChatInput(this), this);
            getServer().getPluginManager().registerEvents(this, this);
            for (String name : List.of("lifecore", "mode", "modegui", "livesgui", "langgui", "market", "challenge")) {
                Objects.requireNonNull(getCommand(name), "Missing plugin command: " + name).setTabCompleter(this);
            }
            String key = secret("ai.api-key-env", "LIFECORE_OPENAI_API_KEY", "ai.api-key");
            if (getConfig().getBoolean("ai.enabled", false) && !key.isBlank()) {
                ai = new OpenAIService(key, getConfig().getString("ai.model", "gpt-4.1-mini"),
                        URI.create(getConfig().getString("ai.endpoint", "https://api.openai.com/v1/chat/completions")),
                        Duration.ofSeconds(bounded("ai.timeout-seconds", 30, 1, 120)));
            }
            onlineEnabled = getConfig().getBoolean("online.enabled", true);
            autoDownload = getConfig().getBoolean("updates.auto-download", false);
            SafeHttpClient http = new SafeHttpClient(Duration.ofSeconds(bounded("online.timeout-seconds", 15, 1, 120)),
                    Set.copyOf(getConfig().getStringList("online.allowed-hosts")), false);
            URI catalogue = URI.create(getConfig().getString("marketplace.catalog-url"));
            challengeIndex = URI.create(getConfig().getString("challenges.index-url"));
            URI releases = URI.create(getConfig().getString("updates.releases-url"));
            boolean refreshMarket = getConfig().getBoolean("online.refresh-on-start", true);
            boolean refreshChallenges = getConfig().getBoolean("challenges.refresh-on-start", true);
            boolean checkUpdates = getConfig().getBoolean("updates.check-on-start", true);
            int flushSeconds = bounded("storage.flush-seconds", 30, 5, 3600);
            int updateMinutes = bounded("updates.interval-minutes", 360, 60, 10080);
            Supplier<Storage> factory = storageFactory();
            Map<UUID, PlayerData> legacy = legacyData();
            List<UUID> alreadyOnline = getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList();
            if (!Files.exists(getDataFolder().toPath().resolve("challenges/stone_miner.yml"))) {
                saveResource("challenges/stone_miner.yml", false);
            }
            java.nio.file.Path updateFolder = getServer().getUpdateFolderFile().toPath();
            String runningVersion = getDescription().getVersion();
            String runningJar = getFile().getName();
            workers.execute(() -> {
                PlayerDataService service = null;
                try {
                    service = new PlayerDataService(factory.get(), activeMode.startingLives());
                    ChallengeManager manager = new ChallengeManager(getDataFolder().toPath().resolve("challenges"), service);
                    manager.loadChallenges();
                    Map<UUID, PlayerData> imported = new HashMap<>();
                    for (var entry : legacy.entrySet()) {
                        PlayerData snapshot = entry.getValue();
                        for (Challenge challenge : manager.getChallenges().values()) {
                            if (snapshot.challengeProgress().getOrDefault(challenge.getId(), 0) >= challenge.getGoal())
                                snapshot = snapshot.completeChallenge(challenge.getId());
                        }
                        imported.put(entry.getKey(), snapshot);
                    }
                    service.migrateLegacy(imported).join();
                    for (UUID id : alreadyOnline) service.load(id, activeMode.startingLives()).join();
                    synchronized (lifecycleLock) {
                        if (stopping) { service.close(); return; }
                        data = service;
                    }
                    challenges = manager;
                    marketplace = new MarketplaceManager(catalogue, http, modes, manager);
                    challengeDownloader = new GitHubChallengeDownloader(manager, http);
                    updater = new UpdateManager(runningVersion, releases, http, updateFolder, runningJar);
                    mainThread(() -> {
                        getServer().getPluginManager().registerEvents(new ChallengeListener(this, manager), this);
                        ready = true;
                        getServer().getScheduler().runTaskTimer(this, () -> data.flush().exceptionally(error -> {
                            getLogger().severe("Player data flush failed; changes remain pending for retry."); return null;
                        }), flushSeconds * 20L, flushSeconds * 20L);
                        if (getConfig().getBoolean("settings.actionbar", true)) {
                            getServer().getScheduler().runTaskTimer(this, this::actionBars, 1,
                                    bounded("settings.update-interval", 40, 2, 1200));
                        }
                        if (onlineEnabled) {
                            if (refreshMarket) async(getServer().getConsoleSender(), marketplace::refresh,
                                    ignored -> getLogger().info("Marketplace catalogue refreshed."));
                            if (refreshChallenges) refreshChallenges(getServer().getConsoleSender());
                            if (checkUpdates) checkUpdate(getServer().getConsoleSender(), autoDownload);
                            getServer().getScheduler().runTaskTimer(this,
                                    () -> checkUpdate(getServer().getConsoleSender(), autoDownload),
                                    updateMinutes * 1200L, updateMinutes * 1200L);
                        }
                        getLogger().info("LifeCore 2.1 ready: " + modes.all().size() + " modes, "
                                + manager.getChallenges().size() + " challenges.");
                    });
                } catch (Exception error) {
                    if (service != null) try { service.close(); } catch (Exception ignored) { }
                    getLogger().severe("Storage/startup initialization failed (" + rootCause(error).getClass().getSimpleName()
                            + "). No fallback or data reset was performed; check configuration and database connectivity.");
                    mainThread(() -> getServer().getPluginManager().disablePlugin(this));
                }
            });
        } catch (Exception error) {
            getLogger().severe("Invalid LifeCore configuration (" + error.getClass().getSimpleName() + "). Startup stopped.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private Supplier<Storage> storageFactory() {
        String type = getConfig().getString("storage.type", "FILE").toUpperCase(Locale.ROOT);
        if (type.equals("FILE")) return () -> new FileStorage(getDataFolder().toPath());
        if (!type.equals("MYSQL")) throw new IllegalArgumentException("storage.type must be FILE or MYSQL");
        String host = getConfig().getString("storage.mysql.host", "localhost");
        String database = getConfig().getString("storage.mysql.database", "lifecore");
        if (!host.matches("[A-Za-z0-9._-]+") || !database.matches("[A-Za-z0-9_]{1,64}"))
            throw new IllegalArgumentException("Invalid database host or name");
        String sslMode = getConfig().getString("storage.mysql.ssl-mode", "VERIFY_IDENTITY");
        if (!Set.of("VERIFY_IDENTITY", "VERIFY_CA", "REQUIRED", "PREFERRED", "DISABLED").contains(sslMode))
            throw new IllegalArgumentException("Invalid MySQL ssl-mode");
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl("jdbc:mysql://" + host + ":" + bounded("storage.mysql.port", 3306, 1, 65535) + "/" + database);
        pool.setUsername(getConfig().getString("storage.mysql.username", "lifecore"));
        pool.setPassword(secret("storage.mysql.password-env", "LIFECORE_MYSQL_PASSWORD", "storage.mysql.password"));
        pool.setDriverClassName("com.mysql.cj.jdbc.Driver");
        pool.addDataSourceProperty("sslMode", sslMode);
        pool.addDataSourceProperty("connectTimeout", "5000");
        pool.addDataSourceProperty("socketTimeout", "10000");
        pool.setMaximumPoolSize(bounded("storage.mysql.pool-size", 5, 1, 32));
        pool.setMinimumIdle(1);
        pool.setConnectionTimeout(bounded("storage.mysql.connection-timeout-ms", 5000, 1000, 30000));
        return () -> new MySQLStorage(pool);
    }

    private String secret(String setting, String defaultName, String fallback) {
        String name = getConfig().getString(setting, defaultName);
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid credential environment name");
        String value = System.getenv(name);
        return value == null || value.isBlank() ? getConfig().getString(fallback, "") : value;
    }
    private int bounded(String key, int fallback, int min, int max) {
        int value = getConfig().getInt(key, fallback);
        if (value < min || value > max) throw new IllegalArgumentException("Out-of-range configuration: " + key);
        return value;
    }
    private Map<UUID, PlayerData> legacyData() {
        Map<UUID, PlayerData> result = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (String section : List.of("lives", "challenge-progress")) {
            ConfigurationSection found = getConfig().getConfigurationSection(section);
            if (found != null) ids.addAll(found.getKeys(false));
        }
        for (String id : ids) {
            UUID uuid = UUID.fromString(id);
            int lives = getConfig().getInt("lives." + id, getConfig().getInt("default-lives", 3));
            Map<String, Integer> progress = new HashMap<>();
            ConfigurationSection section = getConfig().getConfigurationSection("challenge-progress." + id);
            if (section != null) for (String challenge : section.getKeys(false)) progress.put(challenge, section.getInt(challenge));
            result.put(uuid, new PlayerData(lives, "en", progress, Set.of()));
        }
        return Map.copyOf(result);
    }
    private void bootstrapModes() throws IOException {
        ConfigurationSection old = getConfig().getConfigurationSection("marketplace");
        if (old != null) for (String id : old.getKeys(false)) {
            ConfigurationSection entry = old.getConfigurationSection(id);
            if (entry == null || !entry.contains("lives") || modes.find(id).isPresent()) continue;
            int lives = entry.getInt("lives", 3);
            modes.save(new ModeDefinition(id, entry.getString("name", id), "Imported legacy mode", "PAPER",
                    lives, Math.max(lives, getConfig().getInt("max-lives", 10)), true, false, 0,
                    entry.getBoolean("banOnZero", true)), false);
        }
        List<ModeDefinition> defaults = List.of(
                new ModeDefinition("hardcore", "Hardcore", "One life; zero lives means a ban", "REDSTONE", 1, 10, true, false, 0, true),
                new ModeDefinition("hardcore_plus", "Hardcore+", "Three lives", "REDSTONE_BLOCK", 3, 10, true, false, 0, true),
                new ModeDefinition("lifesteal", "Lifesteal", "Earn a life from player kills", "EMERALD", 3, 20, true, true, 1, false),
                new ModeDefinition("pro", "Pro", "Five starting lives", "DIAMOND", 5, 10, true, false, 0, false),
                new ModeDefinition("vanilla_plus", "Vanilla+", "Three lives, spectator on elimination", "GRASS_BLOCK", 3, 10, true, false, 0, false));
        for (ModeDefinition mode : defaults) if (modes.find(mode.id()).isEmpty()) modes.save(mode, false);
    }

    @Override public void onDisable() {
        PlayerDataService closingData;
        synchronized (lifecycleLock) {
            stopping = true; ready = false;
            closingData = data;
        }
        getServer().getScheduler().cancelTasks(this);
        ChatInput.shutdown();
        if (workers != null) workers.shutdownNow();
        if (closingData != null) try { closingData.close(); } catch (Exception error) {
            getLogger().severe("Final data flush failed. Pending changes could not be confirmed; check storage before restarting.");
        }
    }
    public void mainThread(Runnable task) {
        if (stopping || !isEnabled()) return;
        if (Bukkit.isPrimaryThread()) task.run();
        else try { getServer().getScheduler().runTask(this, () -> { if (!stopping && isEnabled()) task.run(); }); }
        catch (org.bukkit.plugin.IllegalPluginAccessException error) { if (!stopping) throw error; }
    }
    public <T> void async(CommandSender sender, Callable<T> operation, Consumer<T> success) {
        if (!isReady()) { sender.sendMessage("§eLifeCore is still starting."); return; }
        workers.execute(() -> {
            try { T result = operation.call(); mainThread(() -> {
                if (!(sender instanceof Player player) || player.isOnline()) success.accept(result);
            }); } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                String message = rootCause(error).getClass().getSimpleName();
                mainThread(() -> sender.sendMessage("§cOperation failed (" + message + "). Check source/configuration; local data was retained."));
            }
        });
    }
    private static Throwable rootCause(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error;
    }
    public void setActiveMode(String id) throws IOException {
        ModeDefinition selected = modes.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown mode"));
        String previous = getConfig().getString("mode.current");
        getConfig().set("mode.current", id);
        java.nio.file.Path file = getDataFolder().toPath().resolve("config.yml");
        if (Files.isSymbolicLink(file)) {
            getConfig().set("mode.current", previous);
            throw new IOException("Refusing to replace a symbolic configuration file");
        }
        java.nio.file.Path temporary = Files.createTempFile(file.getParent(), ".config-", ".tmp");
        try {
            Files.writeString(temporary, getConfig().saveToString(), java.nio.charset.StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            activeMode = selected;
        } catch (IOException error) {
            getConfig().set("mode.current", previous);
            throw error;
        } finally { Files.deleteIfExists(temporary); }
    }
    public int changeLives(UUID uuid, int delta) {
        return data.update(uuid, previous -> previous.withLives(
                LifeRules.adjust(previous.lives(), delta, activeMode.maxLives()))).lives();
    }
    public void setLanguage(UUID uuid, String language) {
        if (!Set.of("de", "en").contains(language)) throw new IllegalArgumentException("Unsupported language");
        data.update(uuid, previous -> previous.withLanguage(language));
    }
    public void saveMode(Player player, ModeDefinition mode, boolean overwrite, boolean activate) {
        if (!permission(player, "life.mode")) return;
        async(player, () -> { modes.save(mode, overwrite); return mode; }, saved -> {
            try {
                if (activate || activeMode.id().equals(saved.id())) setActiveMode(saved.id());
                player.sendMessage("§aMode saved: " + saved.name()); menus.openModes(player);
            } catch (IOException error) { player.sendMessage("§cCould not activate the saved mode."); }
        });
    }
    public void requestAi(Player player, String id, String description) {
        if (!permission(player, "life.ai") || !permission(player, "life.mode")) return;
        if (ai == null) { player.sendMessage("§eEnable ai.enabled and configure LIFECORE_OPENAI_API_KEY to use AI. The manual builder is available."); return; }
        player.sendMessage("§7Generating a mode preview...");
        async(player, () -> ai.createMode(id, description), generated -> {
            player.sendMessage("§aPreview generated. Save it explicitly in the builder.");
            menus.openBuilder(player, generated, modes.find(id).isPresent());
        });
    }
    public void refreshMarket(Player player) {
        if (!permission(player, "life.market") || !online(player)) return;
        async(player, marketplace::refresh, entries -> { player.sendMessage("§aCatalogue refreshed: " + entries.size()); menus.openMarket(player); });
    }
    public void installMarket(Player player, String id, boolean overwrite) {
        if (!permission(player, "life.market") || !online(player)) return;
        async(player, () -> marketplace.install(id, overwrite), result -> {
            modes.find(activeMode.id()).ifPresent(mode -> activeMode = mode);
            player.sendMessage("§aInstalled " + result.id() + ": " + result.modeCount() + " modes, " + result.challengeCount() + " challenges.");
            menus.openMarket(player);
        });
    }
    private void refreshChallenges(CommandSender sender) {
        if (!online(sender)) return;
        async(sender, () -> challengeDownloader.sync(challengeIndex, false), result ->
                sender.sendMessage("§aChallenges refreshed: " + result.installed() + " installed, " + result.skipped() + " retained."));
    }
    private void checkUpdate(CommandSender sender, boolean download) {
        if (!online(sender)) return;
        async(sender, () -> {
            var found = updater.check();
            if (found.isEmpty()) return "No newer compatible verified release found.";
            var update = found.get();
            if (download) return "Update " + update.version() + " verified and staged at " + updater.download(update).getFileName() + "; restart to apply.";
            return "Update available: " + update.version() + ". Use /lifecore update download to stage it.";
        }, message -> sender.sendMessage("§a" + message));
    }
    private boolean online(CommandSender sender) {
        if (onlineEnabled) return true;
        sender.sendMessage("§eOnline features are disabled in config."); return false;
    }
    private boolean permission(CommandSender sender, String node) {
        if (sender.hasPermission(node)) return true;
        sender.sendMessage("§cMissing permission: " + node); return false;
    }
    private Player player(CommandSender sender) {
        if (sender instanceof Player player && data.isLoaded(player.getUniqueId())) return player;
        sender.sendMessage("§eThis action needs an online player with loaded data."); return null;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("lifecore") && args.length > 0 && args[0].equalsIgnoreCase("status")) {
            if (permission(sender, "life.admin")) sender.sendMessage("LifeCore " + getDescription().getVersion() + ": "
                    + (isReady() ? "ready, mode " + getServerMode() : "starting/unavailable") + ", AI " + (ai == null ? "not configured" : "configured"));
            return true;
        }
        if (!isReady()) { sender.sendMessage("§eLifeCore storage is starting or unavailable. Try again shortly."); return true; }
        try {
            switch (command.getName()) {
                case "lifecore" -> {
                    if (!permission(sender, "life.admin")) return true;
                    if (args.length > 0 && args[0].equalsIgnoreCase("flush")) {
                        async(sender, () -> { data.flush().join(); return true; }, ignored -> sender.sendMessage("§aPlayer data flushed."));
                    } else if (args.length > 0 && args[0].equalsIgnoreCase("update")) {
                        if (permission(sender, "life.update")) checkUpdate(sender, args.length > 1 && args[1].equalsIgnoreCase("download"));
                    } else sender.sendMessage("/lifecore status | flush | update [check|download]");
                }
                case "mode", "modegui" -> {
                    if (!permission(sender, "life.mode")) return true;
                    if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
                        sender.sendMessage("Modes: " + String.join(", ", modes.all().stream().map(ModeDefinition::id).toList()));
                    } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                        setActiveMode(args[1]); sender.sendMessage("§aActive mode: " + getServerMode());
                    } else {
                        Player player = player(sender); if (player == null) return true;
                        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
                            String id = ModeDefinition.validateId(args[1]);
                            if (modes.find(id).isPresent()) throw new IllegalArgumentException("Mode already exists; use edit");
                            menus.openBuilder(player, new ModeDefinition(id, id, "", "PAPER", 3, 10, true, false, 1, false), false);
                        } else if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
                            menus.openBuilder(player, modes.find(args[1]).orElseThrow(() -> new IllegalArgumentException("Unknown mode")), true);
                        } else if (args.length >= 3 && args[0].equalsIgnoreCase("ai")) {
                            requestAi(player, ModeDefinition.validateId(args[1]), String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                        } else if (args.length == 0) menus.openModes(player);
                        else sender.sendMessage("/mode list | set <id> | create <id> | edit <id> | ai <id> <description>");
                    }
                }
                case "livesgui" -> { if (permission(sender, "life.admin")) { Player p = player(sender); if (p != null) menus.openLives(p); } }
                case "langgui" -> { if (permission(sender, "life.language")) { Player p = player(sender); if (p != null) menus.openLanguages(p); } }
                case "market" -> {
                    if (!permission(sender, "life.market")) return true;
                    Player p = player(sender); if (p == null) return true;
                    if (args.length == 1 && args[0].equalsIgnoreCase("refresh")) refreshMarket(p);
                    else if ((args.length == 2 || args.length == 3) && args[0].equalsIgnoreCase("install"))
                        installMarket(p, args[1], args.length == 3 && args[2].equals("--replace"));
                    else { menus.openMarket(p); if (marketplace.entries().isEmpty() && onlineEnabled) refreshMarket(p); }
                }
                case "challenge" -> {
                    if (!permission(sender, "life.challenge")) return true;
                    if (args.length == 1 && args[0].equalsIgnoreCase("refresh")) {
                        if (permission(sender, "life.online")) refreshChallenges(sender);
                    } else {
                        Player p = player(sender); if (p == null) return true;
                        PlayerData snapshot = data.get(p.getUniqueId());
                        for (Challenge challenge : challenges.getChallenges().values()) sender.sendMessage("§e" + challenge.getName()
                                + " §7" + snapshot.challengeProgress().getOrDefault(challenge.getId(), 0) + "/" + challenge.getGoal()
                                + (snapshot.completedChallenges().contains(challenge.getId()) ? " §a✓" : " §7(" + challenge.getReward() + " XP)"));
                    }
                }
                default -> { return false; }
            }
        } catch (IllegalArgumentException | IOException error) { sender.sendMessage("§c" + error.getMessage()); }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!isReady()) return List.of();
        List<String> options = List.of();
        switch (command.getName()) {
            case "mode" -> { if (!sender.hasPermission("life.mode")) return List.of();
                if (args.length == 1) options = List.of("list", "set", "create", "edit", "ai");
                else if (args.length == 2 && Set.of("set", "edit").contains(args[0])) options = modes.all().stream().map(ModeDefinition::id).toList(); }
            case "lifecore" -> { if (sender.hasPermission("life.admin") && args.length == 1) options = List.of("status", "flush", "update"); }
            case "market" -> { if (sender.hasPermission("life.market") && args.length == 1) options = List.of("refresh", "install"); }
            case "challenge" -> { if (sender.hasPermission("life.online") && args.length == 1) options = List.of("refresh"); }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    @EventHandler public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (!isReady()) { event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "LifeCore storage is starting. Please reconnect shortly."); return; }
        try { data.load(event.getUniqueId(), activeMode.startingLives()).get(15, TimeUnit.SECONDS); }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "LifeCore could not load your data. Please retry later.");
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        if (data != null && data.isLoaded(event.getPlayer().getUniqueId())) data.save(event.getPlayer().getUniqueId()).exceptionally(error -> {
            getLogger().warning("A disconnect save is pending storage recovery."); return null;
        });
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        if (isReady() && data.isLoaded(event.getPlayer().getUniqueId()) && getLives(event.getPlayer()) == 0)
            mainThread(() -> eliminated(event.getPlayer()));
    }
    @EventHandler(priority = EventPriority.MONITOR) public void death(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!isReady() || !data.isLoaded(victim.getUniqueId())) return;
        ModeDefinition rules = activeMode;
        if (rules.loseOnDeath()) data.update(victim.getUniqueId(), previous -> previous.withLives(LifeRules.afterDeath(previous.lives(), rules)));
        Player killer = victim.getKiller();
        if (rules.stealOnKill() && killer != null && !killer.getUniqueId().equals(victim.getUniqueId()) && data.isLoaded(killer.getUniqueId()))
            changeLives(killer.getUniqueId(), rules.stealAmount());
        victim.sendMessage("§cLives remaining: " + getLives(victim));
        if (getLives(victim) == 0 && rules.banOnZero()) eliminated(victim);
    }
    @EventHandler public void respawn(PlayerRespawnEvent event) {
        getServer().getScheduler().runTask(this, () -> {
            if (isReady() && event.getPlayer().isOnline() && data.isLoaded(event.getPlayer().getUniqueId()) && getLives(event.getPlayer()) == 0)
                eliminated(event.getPlayer());
        });
    }
    @SuppressWarnings("deprecation") private void eliminated(Player player) {
        if (activeMode.banOnZero()) {
            Bukkit.getBanList(BanList.Type.NAME).addBan(player.getName(), "No lives remaining", (Date) null, "LifeCore");
            player.kickPlayer("No lives remaining");
        } else player.setGameMode(GameMode.SPECTATOR);
    }
    private void actionBars() {
        if (!isReady()) return;
        for (Player player : getServer().getOnlinePlayers()) if (data.isLoaded(player.getUniqueId()))
            ActionBarUtil.send(player, "§cLives: §f" + getLives(player) + " §7| §bMode: §f" + activeMode.name());
    }
}
