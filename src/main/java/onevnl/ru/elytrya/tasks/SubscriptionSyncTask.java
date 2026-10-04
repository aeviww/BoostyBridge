package onevnl.ru.elytrya.tasks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import onevnl.ru.elytrya.BoostyBridge;
import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.api.managers.BlogManager;
import onevnl.ru.elytrya.api.managers.RewardManager;
import onevnl.ru.elytrya.models.BoostyUser;
import onevnl.ru.elytrya.models.SubscriberSnapshot;
import onevnl.ru.elytrya.util.BoostyNameValidator;
import onevnl.ru.elytrya.util.RewardLevels;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class SubscriptionSyncTask extends BukkitRunnable {

  public record SyncResult(boolean success, int checked, int revoked, int updated, int failed, String reason) {
    public static SyncResult failure(String reason) { return new SyncResult(false, 0, 0, 0, 0, reason); }
  }

  private final BoostyClient client;

  public SubscriptionSyncTask(BoostyClient client) { this.client = client; }

  @Override
  public void run() { synchronize(); }

  public CompletableFuture<SyncResult> synchronize() {
    client.getRewardManager().flushAll();
    if (!client.getSyncRunning().compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(SyncResult.failure("Синхронизация уже выполняется. Дождитесь её завершения."));
    }
    client.getPlugin().getLogger().info("Subscription sync started.");
    return client.getAuthManager().checkAndRefreshToken()
      .thenCompose(valid -> Boolean.FALSE.equals(valid)
        ? CompletableFuture.completedFuture(SubscriberSnapshot.failed("Невалидный или отсутствующий auth.auth_data в config.yml"))
        : client.getBlogManager().getSubscribersSnapshot())
      .orTimeout(90, TimeUnit.SECONDS)
      .thenCompose(this::process)
      .exceptionally(error -> {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return SyncResult.failure("Ошибка синхронизации: " + cause.getClass().getSimpleName());
      })
      .whenComplete((result, error) -> {
        client.getSyncRunning().set(false);
        client.getRewardManager().flushAll();
        if (result != null) {
          if (!result.success()) client.getPlugin().getLogger().warning("Subscription sync failed: " + result.reason());
          else client.getPlugin().getLogger().info("Subscription sync finished: checked=" + result.checked() + ", revoked=" + result.revoked() + ", updated=" + result.updated() + ", failed=" + result.failed());
        }
      });
  }

  public void reply(CommandSender sender, SyncResult result) {
    Bukkit.getScheduler().runTask(client.getPlugin(), () -> {
      String message;
      if (!result.success()) {
        message = client.getMessageManager().getMessage("admin_forcesync_failed", "&cСинхронизация не выполнена: %reason%").replace("%reason%", result.reason());
      } else {
        message = client.getMessageManager().getMessage("admin_forcesync_finished", "&aСинхронизация завершена: проверено %checked%, отписок %revoked%, изменений уровней %updated%, ошибок %failed%.")
          .replace("%checked%", String.valueOf(result.checked())).replace("%revoked%", String.valueOf(result.revoked()))
          .replace("%updated%", String.valueOf(result.updated())).replace("%failed%", String.valueOf(result.failed()));
      }
      sender.sendMessage(message);
    });
  }

  private CompletableFuture<SyncResult> process(SubscriberSnapshot snapshot) {
    if (snapshot == null || !snapshot.success()) return CompletableFuture.completedFuture(SyncResult.failure(snapshot == null ? "Boosty не вернул ответ" : snapshot.failureReason()));
    List<BoostyUser> users = client.getDatabase().getAllUsers();
    Set<String> claimed = new HashSet<>();
    List<CompletableFuture<Integer>> operations = new ArrayList<>();
    for (BoostyUser user : users) {
      if (!claimed.add(BlogManager.subscriberKey(user.boostyName()))) {
        operations.add(CompletableFuture.completedFuture(-1));
        client.getPlugin().getLogger().warning("Duplicate Boosty link for " + user.playerName());
        continue;
      }
      CompletableFuture<Integer> operation = new CompletableFuture<>();
      operations.add(operation);
      Bukkit.getScheduler().runTask(client.getPlugin(), () -> {
        try {
          syncUser(user, snapshot.subscribers()).whenComplete((value, error) -> {
            if (error != null) {
              client.getPlugin().getLogger().warning("Subscription update failed for " + user.playerName() + ": " + error.getClass().getSimpleName());
              operation.complete(-1);
            } else operation.complete(value);
          });
        } catch (RuntimeException e) {
          client.getPlugin().getLogger().warning("Subscription update failed for " + user.playerName() + ": " + e.getMessage());
          operation.complete(-1);
        }
      });
    }
    return CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
      int revoked = 0, updated = 0, failed = 0;
      for (CompletableFuture<Integer> operation : operations) {
        int value = operation.join();
        if (value == 1) revoked++;
        if (value == 2) updated++;
        if (value == -1) failed++;
      }
      return new SyncResult(true, users.size(), revoked, updated, failed, null);
    });
  }

  private CompletableFuture<Integer> syncUser(BoostyUser snapshotUser, Map<String, String> subscribers) {
    BoostyUser user = client.getDatabase().getUser(snapshotUser.uuid());
    if (user == null || !BoostyNameValidator.key(user.boostyName()).equals(BoostyNameValidator.key(snapshotUser.boostyName()))) return CompletableFuture.completedFuture(0);
    String next = subscribers.get(BlogManager.subscriberKey(user.boostyName()));
    client.debug("Sync player=" + user.playerName() + ", Boosty=" + user.boostyName().replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ") + ", storedStatus=" + user.subscriptionStatus() + ", storedLevel=" + user.levelName() + ", activeLevel=" + (next == null ? "<none>" : next) + ", decision=" + (next == null ? "revoke-inactive-or-absent" : "check-level"));
    if (next == null) {
      if (!user.isActive()) return CompletableFuture.completedFuture(0);
      return client.getRewardManager().deactivateSubscription(user).thenApply(success -> {
        if (!success) {
          Player pending = Bukkit.getPlayer(user.uuid());
          if (pending != null && pending.isOnline()) pending.sendMessage(client.getMessageManager().getMessage("subscription_ended_pending", "&eОтписка обнаружена, но снятие наград или роли Discord ещё не завершено. Администратору нужно проверить консоль; привязка сохранена для повтора."));
          return -1;
        }
        Player player = Bukkit.getPlayer(user.uuid());
        if (player != null && player.isOnline()) player.sendMessage(client.getMessageManager().getMessage("subscription_inactive", "&cПодписка Boosty неактивна. Награды сняты; привязка сохранена для автоматического восстановления.")
          .replace("%level%", user.levelName()).replace("%boosty%", user.boostyName()));
        client.getDiscordManager().sendNotification("unsubscription", user.playerName(), user.boostyName(), user.levelName());
        return 1;
      });
    }
    String newLevel = RewardLevels.canonical(client.getPlugin().getConfig(), next);
    String oldLevel = RewardLevels.canonical(client.getPlugin().getConfig(), user.levelName());
    if (!user.isActive()) {
      client.getDatabase().activateAndQueueReward(user, newLevel);
      client.getRewardManager().flush(user.uuid(), user.playerName());
      if (client.getPlugin().getConfig().getBoolean("discord.bot.enabled", false)) ((BoostyBridge) client.getPlugin()).getDiscordBotManager().syncRoleAndPrompt(user.uuid(), user.playerName(), null, newLevel);
      Player player = Bukkit.getPlayer(user.uuid());
      if (player != null && player.isOnline()) player.sendMessage(client.getMessageManager().getMessage("subscription_reactivated", "&aПодписка Boosty снова активна. Награды восстановлены или поставлены в очередь.").replace("%level%", newLevel));
      client.getDiscordManager().sendNotification("subscription", user.playerName(), user.boostyName(), newLevel);
      client.debug("Subscription reactivated for " + user.playerName() + ": give queued for level " + newLevel);
      return CompletableFuture.completedFuture(2);
    }
    if (newLevel.equalsIgnoreCase(oldLevel)) {
      if (!user.levelName().equals(newLevel)) client.getDatabase().updateLevel(user.uuid(), newLevel);
      return CompletableFuture.completedFuture(0);
    }
    CompletableFuture<Integer> changed = new CompletableFuture<>();
    client.getRewardManager().removeDiscordRole(user).whenComplete((removed, error) -> Bukkit.getScheduler().runTask(client.getPlugin(), () -> {
      try {
        if (error != null || !Boolean.TRUE.equals(removed)) {
          client.getPlugin().getLogger().warning("Cannot remove old Discord role for " + user.playerName() + "; level change retained for retry.");
          changed.complete(-1);
          return;
        }
        BoostyUser current = client.getDatabase().getUser(user.uuid());
        if (current == null || !current.equals(user)) { changed.complete(0); return; }
        client.getRewardManager().dispatch(user.uuid(), user.playerName(), user.boostyName(), user.levelName(), RewardManager.ACTION_TAKE);
        client.getDatabase().updateLevel(user.uuid(), newLevel);
        client.getRewardManager().dispatch(user.uuid(), user.playerName(), user.boostyName(), newLevel, RewardManager.ACTION_GIVE);
        if (client.getPlugin().getConfig().getBoolean("discord.bot.enabled", false)) ((BoostyBridge) client.getPlugin()).getDiscordBotManager().syncRoleAndPrompt(user.uuid(), user.playerName(), null, newLevel);
        Player player = Bukkit.getPlayer(user.uuid());
        if (player != null && player.isOnline()) player.sendMessage(client.getMessageManager().getMessage("subscription_level_changed", "&eВаш уровень Boosty изменился: %old% → %new%.").replace("%old%", user.levelName()).replace("%new%", newLevel));
        client.getDiscordManager().sendNotification("subscription", user.playerName(), user.boostyName(), newLevel);
        changed.complete(2);
      } catch (RuntimeException e) { changed.completeExceptionally(e); }
    }));
    return changed;
  }
}
