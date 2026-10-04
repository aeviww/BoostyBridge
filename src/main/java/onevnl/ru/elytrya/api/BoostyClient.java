package onevnl.ru.elytrya.api;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;

import onevnl.ru.elytrya.api.managers.AuthManager;
import onevnl.ru.elytrya.api.managers.BlogManager;
import onevnl.ru.elytrya.api.managers.BoostyDMManager;
import onevnl.ru.elytrya.api.managers.DiscordManager;
import onevnl.ru.elytrya.api.managers.MessageManager;
import onevnl.ru.elytrya.api.managers.RewardManager;
import onevnl.ru.elytrya.database.Database;
import onevnl.ru.elytrya.database.MySQL;
import onevnl.ru.elytrya.database.SQLite;
import onevnl.ru.elytrya.models.PendingDiscordLink;
import onevnl.ru.elytrya.models.PendingLink;
import onevnl.ru.elytrya.tasks.PendingLinkCleanupTask;

public class BoostyClient {
    private DiscordManager discordManager;
    private BoostyDMManager dmManager;
    private final JavaPlugin plugin;
    private final HttpClient httpClient;
    private final Gson gson;

    private MessageManager messageManager;
    private AuthManager authManager;
    private BlogManager blogManager;
    private Database database;
    private RewardManager rewardManager;

    private final Map<UUID, PendingLink> pendingLinks;
    private final Map<UUID, PendingDiscordLink> pendingDiscordLinks;
    private final Map<UUID, onevnl.ru.elytrya.models.PendingDiscordConfirm> pendingDiscordConfirms;
    private final java.util.concurrent.atomic.AtomicBoolean syncRunning = new java.util.concurrent.atomic.AtomicBoolean();
    public java.util.concurrent.atomic.AtomicBoolean getSyncRunning() { return syncRunning; }
    private org.bukkit.scheduler.BukkitTask syncTask;
    private org.bukkit.scheduler.BukkitTask pendingLinkCleanupTask;
    private org.bukkit.scheduler.BukkitTask rewardQueueTask;

    public BoostyClient(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
        this.pendingLinks = new ConcurrentHashMap<>();
        this.pendingDiscordLinks = new ConcurrentHashMap<>();
        this.pendingDiscordConfirms = new ConcurrentHashMap<>();

        loadManagers();
    }

    private void loadManagers() {
        if (rewardQueueTask != null) rewardQueueTask.cancel();
        this.messageManager = new MessageManager(plugin);
        this.authManager = new AuthManager(this);
        this.blogManager = new BlogManager(this);
        this.discordManager = new DiscordManager(this);
        this.dmManager = new BoostyDMManager(this);
        this.rewardManager = new RewardManager(this);

        if (this.database != null) {
            this.database.disconnect();
        }

        String dbType = plugin.getConfig().getString("database.type", "SQLITE").toUpperCase();
        if (dbType.equals("MYSQL")) {
            this.database = new MySQL(plugin);
        } else {
            this.database = new SQLite(plugin);
        }
        this.database.connect();

        long queueInterval = Math.max(20L, plugin.getConfig().getLong("rewards_queue.poll_interval_ticks", 200L));
        rewardQueueTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> rewardManager.flushAll(), Math.max(1L, rewardManager.getDeliveryDelayTicks()), queueInterval);

        if (syncTask != null) {
            syncTask.cancel();
        }
        long interval = plugin.getConfig().getLong("sync.interval_minutes", 60) * 60 * 20L;
        syncTask = new onevnl.ru.elytrya.tasks.SubscriptionSyncTask(this).runTaskTimerAsynchronously(plugin, 40L, Math.max(20L, interval));

        if (pendingLinkCleanupTask != null) {
            pendingLinkCleanupTask.cancel();
        }
        pendingLinkCleanupTask = new PendingLinkCleanupTask(this).runTaskTimerAsynchronously(plugin, 1200L, 1200L);
    }

    public void reload() {
        plugin.reloadConfig();
        loadManagers();
        pendingLinks.clear();
        pendingDiscordLinks.clear();
        pendingDiscordConfirms.clear();
    }

    public void disable() {
        if (rewardQueueTask != null) rewardQueueTask.cancel();
        if (syncTask != null) {
            syncTask.cancel();
        }
        if (pendingLinkCleanupTask != null) {
            pendingLinkCleanupTask.cancel();
        }
        if (database != null) {
            database.disconnect();
        }
        pendingLinks.clear();
        pendingDiscordLinks.clear();
        pendingDiscordConfirms.clear();
    }

    public void debug(String message) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public Gson getGson() {
        return gson;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public DiscordManager getDiscordManager() {
        return discordManager;
    }

    public BoostyDMManager getDmManager() {
        return dmManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public BlogManager getBlogManager() {
        return blogManager;
    }

    public RewardManager getRewardManager() {
        return rewardManager;
    }

    public Database getDatabase() {
        return database;
    }

    public Map<UUID, PendingLink> getPendingLinks() {
        return pendingLinks;
    }

    public Map<UUID, PendingDiscordLink> getPendingDiscordLinks() {
        return pendingDiscordLinks;
    }

    public Map<UUID, onevnl.ru.elytrya.models.PendingDiscordConfirm> getPendingDiscordConfirms() {
        return pendingDiscordConfirms;
    }
}
