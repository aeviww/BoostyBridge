package onevnl.ru.elytrya.tasks;

import java.util.List;
import java.util.Map;
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
    client.debug("Starting subscription sync task...");

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
      client.debug("Sync finished: no linked players in the database.");
      return;
    }

    Map<String, String> subscribers = snapshot.subscribers();

    for (BoostyUser user : users) {
      try {
        syncUser(user, subscribers);
      } catch (Exception e) {
        client
          .getPlugin()
          .getLogger()
          .warning(
            "Skipping " +
            user.playerName() +
            " during sync: " +
            e.getClass().getSimpleName()
          );
      }
    }

    client.debug(
      "Sync finished: " +
      users.size() +
      " links checked, " +
      subscribers.size() +
      " subscribers on Boosty."
    );
  }

  private void syncUser(BoostyUser user, Map<String, String> subscribers) {
    String currentLevel = user.levelName();
    String newLevel = subscribers.get(
      BlogManager.subscriberKey(user.boostyName())
    );

    if (newLevel == null) {
      client.debug("player " + user.playerName() + " lost their subscription!");

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
      return;
    }

    if (newLevel.equalsIgnoreCase(currentLevel)) return;

    client.debug(
      "player " +
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
  }

  private void skipRun(String reason) {
    client
      .getPlugin()
      .getLogger()
      .warning(
        "Subscription sync skipped: " +
        (reason == null ? "unknown error" : reason) +
        ". Rewards and links are untouched."
      );
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
