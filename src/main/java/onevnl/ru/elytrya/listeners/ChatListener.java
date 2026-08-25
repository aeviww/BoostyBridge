package onevnl.ru.elytrya.listeners;

import java.util.List;
import java.util.UUID;
import onevnl.ru.elytrya.BoostyBridge;
import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.api.managers.MessageManager;
import onevnl.ru.elytrya.models.BoostyUser;
import onevnl.ru.elytrya.models.PendingDiscordLink;
import onevnl.ru.elytrya.models.PendingLink;
import onevnl.ru.elytrya.util.BoostyNameValidator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import onevnl.ru.elytrya.api.managers.RewardManager;
import onevnl.ru.elytrya.util.RewardLevels;

public class ChatListener implements Listener {

  private final BoostyClient client;

  public ChatListener(BoostyClient client) {
    this.client = client;
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onChat(AsyncPlayerChatEvent event) {
    Player player = event.getPlayer();
    UUID uuid = player.getUniqueId();

    PendingDiscordLink discordPending = client
      .getPendingDiscordLinks()
      .get(uuid);
    if (discordPending != null) {
      event.setCancelled(true);
      event.getRecipients().clear();
      handleDiscordCode(player, uuid, discordPending, event.getMessage());
      return;
    }

    PendingLink pending = client.getPendingLinks().get(uuid);
    if (pending == null) {
      return;
    }

    event.setCancelled(true);
    event.getRecipients().clear();

    MessageManager msg = client.getMessageManager();
    String input = ChatColor.stripColor(event.getMessage()).trim();

    if (pending.isExpired()) {
      client.getPendingLinks().remove(uuid);
      player.sendMessage(
        msg.getMessage(
          "link_code_expired",
          "&c\u041a\u043e\u0434 \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0436\u0434\u0435\u043d\u0438\u044f \u0438\u0441\u0442\u0451\u043a. \u041d\u0430\u0447\u043d\u0438\u0442\u0435 \u0437\u0430\u043d\u043e\u0432\u043e: /boosty link <\u043d\u0438\u043a>"
        )
      );
      client.debug("Verification code expired for " + player.getName());
      return;
    }

    if (!input.equalsIgnoreCase(pending.verificationValue())) {
      pending.registerFailedAttempt();
      int left = pending.attemptsLeft();

      if (left <= 0) {
        client.getPendingLinks().remove(uuid);
        player.sendMessage(
          msg.getMessage(
            "link_attempts_exceeded",
            "&c\u041f\u043e\u043f\u044b\u0442\u043a\u0438 \u0438\u0441\u0447\u0435\u0440\u043f\u0430\u043d\u044b. \u041d\u0430\u0447\u043d\u0438\u0442\u0435 \u0437\u0430\u043d\u043e\u0432\u043e: /boosty link <\u043d\u0438\u043a>"
          )
        );
      } else {
        player.sendMessage(
          msg
            .getMessage("link_email_fail")
            .replace("%attempts%", String.valueOf(left))
        );
        player.sendMessage(
          msg
            .getMessage(
              "link_attempts_left",
              "&7\u041e\u0441\u0442\u0430\u043b\u043e\u0441\u044c \u043f\u043e\u043f\u044b\u0442\u043e\u043a: %attempts%"
            )
            .replace("%attempts%", String.valueOf(left))
        );
      }

      client.debug("Verification failed for " + player.getName());
      return;
    }

    client.getPendingLinks().remove(uuid);

    if (!BoostyNameValidator.isValid(pending.boostyName())) {
      player.sendMessage(
        msg.getMessage(
          "link_invalid_name",
          "&c\u041d\u0435\u0434\u043e\u043f\u0443\u0441\u0442\u0438\u043c\u043e\u0435 \u0438\u043c\u044f Boosty."
        )
      );
      return;
    }

    client.debug("Verification successful!");

    BoostyBridge plugin = (BoostyBridge) client.getPlugin();

    boolean claimed = client
      .getDatabase()
      .claimLink(
        player.getUniqueId(),
        player.getName(),
        pending.boostyName(),
        pending.levelName()
      );

    if (!claimed) {
      player.sendMessage(msg.getMessage("link_already_linked"));
      client.debug(
        "Link rejected: Boosty account is already linked to another player."
      );
      return;
    }

    player.sendMessage(
      msg.getMessage("link_success").replace("%name%", pending.boostyName())
    );
    msg.broadcastCongratulation(player.getName(), pending.levelName());

    plugin
      .getDiscordBotManager()
      .syncRoleAndPrompt(
        player.getUniqueId(),
        player.getName(),
        null,
        pending.levelName()
      );

    executeRewards(player, pending.boostyName(), pending.levelName());
  }

  private void handleDiscordCode(
    Player player,
    UUID uuid,
    PendingDiscordLink pending,
    String rawMessage
  ) {
    MessageManager msg = client.getMessageManager();
    String input = ChatColor.stripColor(rawMessage).trim();

    if (pending.isExpired()) {
      client.getPendingDiscordLinks().remove(uuid);
      player.sendMessage(
        msg.getMessage(
          "discord_code_expired",
          "&c\u041a\u043e\u0434 \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0436\u0434\u0435\u043d\u0438\u044f Discord \u0438\u0441\u0442\u0451\u043a. \u041d\u0430\u0447\u043d\u0438\u0442\u0435 \u0437\u0430\u043d\u043e\u0432\u043e: /boosty discord <\u044e\u0437\u0435\u0440\u043d\u0435\u0439\u043c>"
        )
      );
      return;
    }

    if (!input.equalsIgnoreCase(pending.code())) {
      pending.registerFailedAttempt();
      int left = pending.attemptsLeft();

      if (left <= 0) {
        client.getPendingDiscordLinks().remove(uuid);
        player.sendMessage(
          msg.getMessage(
            "discord_attempts_exceeded",
            "&c\u041f\u043e\u043f\u044b\u0442\u043a\u0438 \u0438\u0441\u0447\u0435\u0440\u043f\u0430\u043d\u044b. \u041d\u0430\u0447\u043d\u0438\u0442\u0435 \u0437\u0430\u043d\u043e\u0432\u043e: /boosty discord <\u044e\u0437\u0435\u0440\u043d\u0435\u0439\u043c>"
          )
        );
      } else {
        player.sendMessage(
          msg
            .getMessage(
              "discord_code_wrong",
              "&c\u041d\u0435\u0432\u0435\u0440\u043d\u044b\u0439 \u043a\u043e\u0434 \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0436\u0434\u0435\u043d\u0438\u044f."
            )
            .replace("%attempts%", String.valueOf(left))
        );
        player.sendMessage(
          msg
            .getMessage(
              "discord_attempts_left",
              "&7\u041e\u0441\u0442\u0430\u043b\u043e\u0441\u044c \u043f\u043e\u043f\u044b\u0442\u043e\u043a: %attempts%"
            )
            .replace("%attempts%", String.valueOf(left))
        );
      }

      client.debug("Discord verification failed for " + player.getName());
      return;
    }

    client.getPendingDiscordLinks().remove(uuid);
    client.getDatabase().setDiscordUser(uuid, pending.discordName());

    BoostyUser user = client.getDatabase().getUser(uuid);
    String levelName = (user != null) ? user.levelName() : "none";

    ((BoostyBridge) client.getPlugin()).getDiscordBotManager()
      .updateUserRole(
        uuid,
        player.getName(),
        pending.discordName(),
        levelName
      );

    player.sendMessage(
      msg
        .getMessage("discord_success")
        .replace("%discord_tag%", pending.discordName())
    );
    client.debug("Discord verification successful for " + player.getName());
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player joined = event.getPlayer();
    UUID uuid = joined.getUniqueId();

    if (client.getRewardManager().isQueueEnabled()) {
      Bukkit.getScheduler()
        .runTaskLater(
          client.getPlugin(),
          () -> {
            if (!joined.isOnline()) return;
            client.getRewardManager().flush(uuid, joined.getName());
          },
          Math.max(1L, client.getRewardManager().getDeliveryDelayTicks())
        );
    }

    if (!client.getPendingDiscordConfirms().containsKey(uuid)) return;

    Bukkit.getScheduler()
      .runTaskLater(
        client.getPlugin(),
        () ->
          ((BoostyBridge) client.getPlugin()).getDiscordBotManager()
            .sendConfirmPrompt(uuid, true),
        40L
      );
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    client.getPendingLinks().remove(event.getPlayer().getUniqueId());
    client.getPendingDiscordLinks().remove(event.getPlayer().getUniqueId());
  }

  private void executeRewards(
    Player player,
    String boostyName,
    String levelName
  ) {
    if (!BoostyNameValidator.isValid(boostyName)) {
      client
        .getPlugin()
        .getLogger()
        .warning(
          "Reward commands skipped: stored Boosty name failed validation."
        );
      return;
    }

    String safeBoostyName = BoostyNameValidator.sanitize(boostyName);

    List<String> commands = RewardLevels.commands(
      client.getPlugin().getConfig(),
      levelName,
      RewardManager.ACTION_GIVE
    );

    if (commands == null || commands.isEmpty()) {
      client.debug("No rewards found for level: " + levelName);
      return;
    }

    Bukkit.getScheduler().runTask(client.getPlugin(), () -> {
      for (String cmd : commands) {
        String finalCmd = cmd
          .replace("%player%", player.getName())
          .replace("%boosty_name%", safeBoostyName);

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
        client.debug("Executed reward command on main thread: " + finalCmd);
      }
    });
  }
}
