package onevnl.ru.elytrya.api.managers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.models.QueuedReward;
import onevnl.ru.elytrya.util.BoostyNameValidator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import onevnl.ru.elytrya.util.RewardLevels;

public class RewardManager {

  public static final String ACTION_GIVE = "give";
  public static final String ACTION_TAKE = "take";

  private final BoostyClient client;

  public RewardManager(BoostyClient client) {
    this.client = client;
  }

  public boolean isQueueEnabled() {
    return client.getPlugin().getConfig().getBoolean("rewards_queue.enabled", true);
  }

  public long getMaxAgeMillis() {
    long days = client.getPlugin().getConfig().getLong("rewards_queue.max_age_days", 30);
    return days <= 0 ? 0L : days * 24L * 60L * 60L * 1000L;
  }

  public long getDeliveryDelayTicks() {
    return client.getPlugin().getConfig().getLong("rewards_queue.delay_ticks", 40L);
  }

  public void dispatch(UUID uuid, String playerName, String boostyName, String levelName, String action) {
    if (!BoostyNameValidator.isValid(boostyName)) {
      client.getPlugin().getLogger().warning("Reward commands (" + action + ") skipped for " + playerName + ": stored Boosty name failed validation.");
      return;
    }

    if (isOnline(uuid, playerName) || !isQueueEnabled() || uuid == null) {
      runNow(playerName, boostyName, levelName, action);
      return;
    }

    client.getDatabase().queueReward(uuid, action, levelName, boostyName);
    client.debug("Queued " + action + " rewards for offline player " + playerName);
  }

  public void runNow(String playerName, String boostyName, String levelName, String action) {
    String safeBoostyName = BoostyNameValidator.sanitize(boostyName);

    Bukkit.getScheduler().runTask(client.getPlugin(), () -> {
      List<String> commands = RewardLevels.commands(client.getPlugin().getConfig(), levelName, action);
      if (commands.isEmpty()) return;
      for (String cmd : commands) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", playerName).replace("%boosty_name%", safeBoostyName));
      }
    });
  }

  public void flush(UUID uuid, String playerName) {
    if (uuid == null || !isQueueEnabled()) return;

    Bukkit.getScheduler().runTaskAsynchronously(client.getPlugin(), () -> {
      List<QueuedReward> queued = client.getDatabase().getQueuedRewards(uuid);
      if (queued == null || queued.isEmpty()) return;

      client.getDatabase().clearQueuedRewards(uuid);

      long maxAge = getMaxAgeMillis();
      List<QueuedReward> valid = new ArrayList<>();
      for (QueuedReward reward : queued) {
        if (reward.isOlderThan(maxAge)) {
          client.debug("Dropped expired queued reward for " + playerName + " (" + reward.levelName() + ")");
          continue;
        }
        valid.add(reward);
      }

      if (valid.isEmpty()) return;

      for (QueuedReward reward : valid) {
        runNow(playerName, reward.boostyName(), reward.levelName(), reward.action());
      }

      notifyDelivered(uuid, valid);
    });
  }

  private void notifyDelivered(UUID uuid, List<QueuedReward> delivered) {
    Set<String> levels = new LinkedHashSet<>();
    for (QueuedReward reward : delivered) {
      if (ACTION_GIVE.equalsIgnoreCase(reward.action()) && reward.levelName() != null && !reward.levelName().equalsIgnoreCase("none")) {
        levels.add(reward.levelName());
      }
    }

    if (levels.isEmpty()) return;

    String levelList = String.join(", ", levels);
    int count = delivered.size();
    MessageManager msg = client.getMessageManager();

    Bukkit.getScheduler().runTask(client.getPlugin(), () -> {
      Player player = Bukkit.getPlayer(uuid);
      if (player == null || !player.isOnline()) return;
      player.sendMessage(msg.getMessage("reward_queue_delivered", "&f\u041f\u043e\u043a\u0430 \u0432\u0430\u0441 \u043d\u0435 \u0431\u044b\u043b\u043e \u043d\u0430 \u0441\u0435\u0440\u0432\u0435\u0440\u0435, \u0432\u0430\u043c \u043d\u0430\u0447\u0438\u0441\u043b\u0435\u043d\u044b \u043d\u0430\u0433\u0440\u0430\u0434\u044b: &#FFB6C1%level%").replace("%level%", levelList).replace("%count%", String.valueOf(count)));
    });
  }

  private boolean isOnline(UUID uuid, String playerName) {
    Player player = uuid != null ? Bukkit.getPlayer(uuid) : Bukkit.getPlayerExact(playerName);
    return player != null && player.isOnline();
  }
}
