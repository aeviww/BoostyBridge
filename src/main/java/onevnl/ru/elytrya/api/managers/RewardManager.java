package onevnl.ru.elytrya.api.managers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.models.BoostyUser;
import onevnl.ru.elytrya.models.QueuedReward;
import onevnl.ru.elytrya.util.BoostyNameValidator;
import onevnl.ru.elytrya.util.RewardLevels;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class RewardManager {

  public static final String ACTION_GIVE = "give";
  public static final String ACTION_TAKE = "take";

  private final BoostyClient client;
  private final Set<UUID> delivering = ConcurrentHashMap.newKeySet();

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
    if (!ACTION_GIVE.equals(action) && !ACTION_TAKE.equals(action)) return;
    if (!BoostyNameValidator.isValid(boostyName)) {
      warn("Invalid Boosty name for " + playerName + "; reward was not dispatched.");
      return;
    }
    if (ACTION_GIVE.equals(action) && commands(levelName, action).isEmpty()) return;
    if (!isQueueEnabled() || uuid == null) {
      runNow(playerName, boostyName, levelName, action);
      return;
    }
    client.getDatabase().queueReward(uuid, playerName, action, levelName, boostyName);
    flush(uuid, playerName);
  }

  public void runNow(String playerName, String boostyName, String levelName, String action) {
    Runnable task = () -> executeCommands(playerName, boostyName, levelName, action);
    if (Bukkit.isPrimaryThread()) task.run();
    else Bukkit.getScheduler().runTask(client.getPlugin(), task);
  }

  public void flushAll() {
    Bukkit.getScheduler().runTaskAsynchronously(client.getPlugin(), () -> {
      try {
        for (UUID uuid : client.getDatabase().getQueuedRewardPlayers()) flush(uuid, null);
      } catch (RuntimeException e) {
        warn("Cannot scan reward queue: " + e.getMessage());
      }
    });
  }

  public void flush(UUID uuid, String playerName) {
    if (uuid == null || !delivering.add(uuid)) return;
    try {
      Bukkit.getScheduler().runTaskAsynchronously(client.getPlugin(), () -> {
        try {
          List<QueuedReward> queued = client.getDatabase().getQueuedRewards(uuid);
          if (queued.isEmpty()) {
            delivering.remove(uuid);
            return;
          }
          Bukkit.getScheduler().runTask(client.getPlugin(), () -> {
            try {
              if (client.getRewardManager() != this) return;
              deliver(uuid, playerName, queued);
            } catch (RuntimeException e) {
              warn("Reward delivery failed for " + uuid + ": " + e.getMessage());
            } finally {
              delivering.remove(uuid);
            }
          });
        } catch (RuntimeException e) {
          delivering.remove(uuid);
          warn("Cannot read rewards for " + uuid + ": " + e.getMessage());
        }
      });
    } catch (RuntimeException e) {
      delivering.remove(uuid);
      throw e;
    }
  }

  private void deliver(UUID uuid, String fallbackName, List<QueuedReward> queued) {
    List<QueuedReward> delivered = new ArrayList<>();
    BoostyUser linked = client.getDatabase().getUser(uuid);
    Player online = Bukkit.getPlayer(uuid);
    for (QueuedReward reward : queued) {
      boolean give = ACTION_GIVE.equalsIgnoreCase(reward.action());
      boolean take = ACTION_TAKE.equalsIgnoreCase(reward.action());
      if (!give && !take) {
        warn("Unknown queued action " + reward.action() + " for " + uuid);
        break;
      }
      if (give && (reward.isOlderThan(getMaxAgeMillis()) || linked == null || !linked.isActive() || !sameLevel(linked.levelName(), reward.levelName()) || !BoostyNameValidator.key(linked.boostyName()).equals(BoostyNameValidator.key(reward.boostyName())))) {
        if (!client.getDatabase().deleteQueuedReward(reward.id())) break;
        continue;
      }
      List<String> rewardCommands = commands(reward.levelName(), reward.action());
      if (give && rewardCommands.isEmpty()) {
        if (!client.getDatabase().deleteQueuedReward(reward.id())) break;
        continue;
      }
      if (give && isQueueEnabled() && (online == null || !online.isOnline())) continue;
      String name = online != null ? online.getName() : reward.playerName();
      if (name == null) name = fallbackName;
      if (name == null && linked != null) name = linked.playerName();
      if (name == null) name = Bukkit.getOfflinePlayer(uuid).getName();
      if (!executeCommands(name, reward.boostyName(), reward.levelName(), reward.action())) break;
      if (!client.getDatabase().deleteQueuedReward(reward.id())) break;
      if (!rewardCommands.isEmpty()) delivered.add(reward);
    }
    notifyDelivered(uuid, delivered);
  }

  public boolean unlinkRewards(BoostyUser user) {
    if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Unlink must run on the server thread");
    UUID uuid = user.uuid();
    if (!delivering.add(uuid)) return false;
    try {
      if (!revokeForUnlink(user)) return false;
      client.getDatabase().removeLinkAndClearRewards(uuid);
      return true;
    } catch (RuntimeException e) {
      warn("Cannot unlink " + user.playerName() + ": " + e.getMessage());
      return false;
    } finally {
      delivering.remove(uuid);
    }
  }

  private boolean revokeForUnlink(BoostyUser user) {
    UUID uuid = user.uuid();
      Set<String> revokedLevels = new LinkedHashSet<>();
      for (QueuedReward reward : client.getDatabase().getQueuedRewards(uuid)) {
        if (!ACTION_TAKE.equalsIgnoreCase(reward.action())) continue;
        String key = RewardLevels.canonical(client.getPlugin().getConfig(), reward.levelName()).toLowerCase(java.util.Locale.ROOT);
        if (!revokedLevels.add(key)) continue;
        if (!executeCommands(user.playerName(), reward.boostyName(), reward.levelName(), ACTION_TAKE)) return false;
      }
      String currentKey = RewardLevels.canonical(client.getPlugin().getConfig(), user.levelName()).toLowerCase(java.util.Locale.ROOT);
      if (user.isActive() && revokedLevels.add(currentKey) && !executeCommands(user.playerName(), user.boostyName(), user.levelName(), ACTION_TAKE)) return false;
    return true;
  }

  public java.util.concurrent.CompletableFuture<Boolean> removeDiscordRole(BoostyUser user) {
    if (!client.getPlugin().getConfig().getBoolean("discord.bot.enabled", false)
        || RewardLevels.roleId(client.getPlugin().getConfig(), user.levelName()) == 0L) {
      return java.util.concurrent.CompletableFuture.completedFuture(true);
    }
    String discordName = client.getDatabase().getDiscordUser(user.uuid());
    if (discordName == null || discordName.isBlank()) {
      client.debug("Discord role removal skipped for " + user.playerName() + ": no Discord account linked.");
      return java.util.concurrent.CompletableFuture.completedFuture(true);
    }
    onevnl.ru.elytrya.api.managers.DiscordBotManager bot = ((onevnl.ru.elytrya.BoostyBridge) client.getPlugin()).getDiscordBotManager();
    if (bot == null) return java.util.concurrent.CompletableFuture.completedFuture(false);
    return bot.removeUserRole(user.uuid(), user.playerName(), user.levelName()).completeOnTimeout(false, 30, java.util.concurrent.TimeUnit.SECONDS);
  }

  private java.util.concurrent.CompletableFuture<Boolean> removeDiscordRolesForUnlink(BoostyUser user) {
    java.util.Map<Long, BoostyUser> roles = new java.util.LinkedHashMap<>();
    for (QueuedReward reward : client.getDatabase().getQueuedRewards(user.uuid())) {
      if (!ACTION_TAKE.equalsIgnoreCase(reward.action())) continue;
      long id = RewardLevels.roleId(client.getPlugin().getConfig(), reward.levelName());
      if (id != 0L) roles.putIfAbsent(id, new BoostyUser(user.uuid(), user.playerName(), reward.boostyName(), reward.levelName()));
    }
    long currentId = RewardLevels.roleId(client.getPlugin().getConfig(), user.levelName());
    if (currentId != 0L) roles.putIfAbsent(currentId, user);
    java.util.concurrent.CompletableFuture<Boolean> removed = java.util.concurrent.CompletableFuture.completedFuture(true);
    for (BoostyUser role : roles.values()) {
      removed = removed.thenCompose(previous -> Boolean.TRUE.equals(previous) ? removeDiscordRole(role) : java.util.concurrent.CompletableFuture.completedFuture(false));
    }
    return removed;
  }

  public java.util.concurrent.CompletableFuture<Boolean> unlinkWithDiscord(BoostyUser user) {
    return finishSubscription(user, true);
  }

  public java.util.concurrent.CompletableFuture<Boolean> deactivateSubscription(BoostyUser user) {
    return finishSubscription(user, false);
  }

  private java.util.concurrent.CompletableFuture<Boolean> finishSubscription(BoostyUser user, boolean removeLink) {
    java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
    Runnable begin = () -> {
      UUID uuid = user.uuid();
      if (client.getRewardManager() != this || !delivering.add(uuid)) {
        result.complete(false);
        return;
      }
      try {
        BoostyUser current = client.getDatabase().getUser(uuid);
        if (current == null || !BoostyNameValidator.key(current.boostyName()).equals(BoostyNameValidator.key(user.boostyName())) || !sameLevel(current.levelName(), user.levelName()) || !revokeForUnlink(current)) {
          delivering.remove(uuid);
          result.complete(false);
          return;
        }
        removeDiscordRolesForUnlink(current).whenComplete((removed, error) -> Bukkit.getScheduler().runTask(client.getPlugin(), () -> {
          try {
            if (client.getRewardManager() != this || error != null || !Boolean.TRUE.equals(removed)) {
              warn("Unlink for " + current.playerName() + " postponed: Discord role removal failed or manager reloaded; link retained for retry.");
              result.complete(false);
              return;
            }
            if (removeLink) client.getDatabase().removeLinkAndClearRewards(uuid);
            else client.getDatabase().deactivateAndClearRewards(uuid);
            client.getPendingLinks().remove(uuid);
            client.getPendingDiscordLinks().remove(uuid);
            client.getPendingDiscordConfirms().remove(uuid);
            result.complete(true);
          } catch (RuntimeException e) {
            warn("Unlink failed for " + current.playerName() + ": " + e.getMessage());
            result.complete(false);
          } finally {
            delivering.remove(uuid);
          }
        }));
      } catch (RuntimeException e) {
        delivering.remove(uuid);
        warn("Unlink failed for " + user.playerName() + ": " + e.getMessage());
        result.complete(false);
      }
    };
    if (Bukkit.isPrimaryThread()) begin.run();
    else Bukkit.getScheduler().runTask(client.getPlugin(), begin);
    return result;
  }

  private boolean sameLevel(String left, String right) {
    return RewardLevels.canonical(client.getPlugin().getConfig(), left).equalsIgnoreCase(RewardLevels.canonical(client.getPlugin().getConfig(), right));
  }

  private List<String> commands(String level, String action) {
    return RewardLevels.commands(client.getPlugin().getConfig(), level, action.toLowerCase(java.util.Locale.ROOT));
  }

  private boolean executeCommands(String playerName, String boostyName, String levelName, String action) {
    List<String> commands = commands(levelName, action);
    if (commands.isEmpty()) {
      if (ACTION_TAKE.equalsIgnoreCase(action) && RewardLevels.match(client.getPlugin().getConfig(), levelName) == null && !"Follower".equalsIgnoreCase(levelName) && !"none".equalsIgnoreCase(levelName)) {
        warn("No reward configuration for level " + levelName + "; take remains pending.");
        return false;
      }
      return true;
    }
    if (playerName == null || !playerName.matches("[A-Za-z0-9_]{1,16}") || !BoostyNameValidator.isValid(boostyName)) {
      warn("Cannot safely resolve reward command arguments for level " + levelName);
      return false;
    }
    String safeBoostyName = BoostyNameValidator.sanitize(boostyName);
    for (String command : commands) {
      if (command == null || command.isBlank()) continue;
      String resolved = command.replace("%player%", playerName).replace("%boosty_name%", safeBoostyName);
      try {
        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)) {
          warn("Reward command returned false for " + playerName + " (" + action + ", " + levelName + "); entry retained for retry.");
          return false;
        }
      } catch (RuntimeException e) {
        warn("Reward command failed for " + playerName + ": " + e.getClass().getSimpleName());
        return false;
      }
    }
    return true;
  }

  private void notifyDelivered(UUID uuid, List<QueuedReward> delivered) {
    Set<String> levels = new LinkedHashSet<>();
    for (QueuedReward reward : delivered) {
      if (ACTION_GIVE.equalsIgnoreCase(reward.action())) levels.add(reward.levelName());
    }
    Player player = Bukkit.getPlayer(uuid);
    if (levels.isEmpty() || player == null || !player.isOnline()) return;
    player.sendMessage(client.getMessageManager().getMessage("reward_queue_delivered", "Rewards delivered: %level%").replace("%level%", String.join(", ", levels)).replace("%count%", String.valueOf(delivered.size())));
  }

  private void warn(String message) {
    client.getPlugin().getLogger().warning(message);
  }
}
