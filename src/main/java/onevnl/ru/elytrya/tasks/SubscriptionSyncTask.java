package onevnl.ru.elytrya.tasks;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import onevnl.ru.elytrya.BoostyBridge;
import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.api.managers.BlogManager;
import onevnl.ru.elytrya.api.managers.RewardManager;
import onevnl.ru.elytrya.models.BoostyUser;
import onevnl.ru.elytrya.models.SubscriberSnapshot;
import org.bukkit.scheduler.BukkitRunnable;

public class SubscriptionSyncTask extends BukkitRunnable {

  private final BoostyClient client;

  public SubscriptionSyncTask(BoostyClient client) {
    this.client = client;
  }

  @Override
  public void run() {
    log("Subscription sync started.");

    client
      .getAuthManager()
      .checkAndRefreshToken()
      .thenCompose(tokenOk -> {
        if (Boolean.FALSE.equals(tokenOk)) {
          return CompletableFuture.completedFuture(
            SubscriberSnapshot.failed(
              "Boosty token is invalid or missing in config.yml"
            )
          );
        }
        return client.getBlogManager().getSubscribersSnapshot();
      })
      .thenAccept(this::process)
      .exceptionally(ex -> {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        skipRun(cause.getClass().getSimpleName());
        return null;
      });
  }

  private void process(SubscriberSnapshot snapshot) {
    if (snapshot == null) {
      skipRun("no answer from Boosty");
      return;
    }

    if (!snapshot.success()) {
      skipRun(snapshot.failureReason());
      return;
    }

    List<BoostyUser> users = client.getDatabase().getAllUsers();
    if (users == null || users.isEmpty()) {
      log("Subscription sync finished: no linked players in the database.");
      return;
    }

    Map<String, String> subscribers = snapshot.subscribers();
    Set<String> claimed = new HashSet<>();
    int revoked = 0;
    int updated = 0;
    int skipped = 0;

    for (BoostyUser user : users) {
      try {
        if (!claimed.add(BlogManager.subscriberKey(user.boostyName()))) {
          skipped++;
          warn(
            "Skipped " +
            user.playerName() +
            ": Boosty account " +
            user.boostyName() +
            " is linked to more than one player, unlink the extra one: /boosty admin unlink " +
            user.playerName()
          );
          continue;
        }

        int result = syncUser(user, subscribers);
        if (result == 1) revoked++;
        if (result == 2) updated++;
      } catch (Exception e) {
        skipped++;
        warn(
          "Skipped " +
          user.playerName() +
          " during sync: " +
          e.getClass().getSimpleName()
        );
      }
    }

    log(
      "Subscription sync finished. Checked: " +
      users.size() +
      ", subscribers on Boosty: " +
      subscribers.size() +
      ", rewards revoked: " +
      revoked +
      ", levels updated: " +
      updated +
      ", skipped: " +
      skipped
    );
  }

  private int syncUser(BoostyUser user, Map<String, String> subscribers) {
    String currentLevel = user.levelName();
    String newLevel = subscribers.get(
      BlogManager.subscriberKey(user.boostyName())
    );

    if (newLevel == null) {
      log(
        "Player " +
        user.playerName() +
        " (" +
        user.boostyName() +
        ") is no longer subscribed, taking rewards for level " +
        currentLevel
      );

      takeRewards(
        user.uuid(),
        user.playerName(),
        user.boostyName(),
        currentLevel
      );

      ((BoostyBridge) client.getPlugin()).getDiscordBotManager()
        .removeUserRole(user.uuid(), user.playerName(), currentLevel);

      client
        .getDiscordManager()
        .sendNotification(
          "unsubscription",
          user.playerName(),
          user.boostyName(),
          currentLevel
        );

      client.getDatabase().removeLink(user.uuid());
      return 1;
    }

    if (newLevel.equalsIgnoreCase(currentLevel)) return 0;

    log(
      "Player " +
      user.playerName() +
      " changed level: " +
      currentLevel +
      " -> " +
      newLevel
    );

    takeRewards(
      user.uuid(),
      user.playerName(),
      user.boostyName(),
      currentLevel
    );
    giveRewards(user.uuid(), user.playerName(), user.boostyName(), newLevel);

    ((BoostyBridge) client.getPlugin()).getDiscordBotManager()
      .syncRoleAndPrompt(user.uuid(), user.playerName(), null, newLevel);

    client
      .getDiscordManager()
      .sendNotification(
        "subscription",
        user.playerName(),
        user.boostyName(),
        newLevel
      );

    client.getDatabase().updateLevel(user.uuid(), newLevel);
    return 2;
  }

  private void skipRun(String reason) {
    warn(
      "Subscription sync skipped: " +
      (reason == null ? "unknown error" : reason) +
      ". Rewards and links are untouched."
    );
  }

  private void log(String message) {
    client.getPlugin().getLogger().info(message);
  }

  private void warn(String message) {
    client.getPlugin().getLogger().warning(message);
  }

  private void takeRewards(
    UUID uuid,
    String playerName,
    String boostyName,
    String levelName
  ) {
    client
      .getRewardManager()
      .dispatch(
        uuid,
        playerName,
        boostyName,
        levelName,
        RewardManager.ACTION_TAKE
      );
  }

  private void giveRewards(
    UUID uuid,
    String playerName,
    String boostyName,
    String levelName
  ) {
    client
      .getRewardManager()
      .dispatch(
        uuid,
        playerName,
        boostyName,
        levelName,
        RewardManager.ACTION_GIVE
      );
  }
}
