package onevnl.ru.elytrya.commands.subcommands;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import onevnl.ru.elytrya.BoostyBridge;
import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.api.managers.MessageManager;
import onevnl.ru.elytrya.api.managers.RewardManager;
import onevnl.ru.elytrya.models.BoostyUser;
import onevnl.ru.elytrya.tasks.SubscriptionSyncTask;
import onevnl.ru.elytrya.util.BoostyNameValidator;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

public class AdminSubCommand implements SubCommand {

  private static final char QUOTE_DOUBLE = '"';
  private static final char QUOTE_SINGLE = '\'';
  private static final char QUOTE_RU_OPEN = '\u00ab';
  private static final char QUOTE_RU_CLOSE = '\u00bb';

  private final BoostyClient client;

  public AdminSubCommand(BoostyClient client) {
    this.client = client;
  }

  @Override
  public String getName() {
    return "admin";
  }

  @Override
  public String getPermission() {
    return null;
  }

  private record Target(UUID uuid, String name) {}

  private record ForceLinkArgs(String boostyName, String level) {}

  @Override
  public void execute(CommandSender sender, String[] args) {
    MessageManager msg = client.getMessageManager();

    if (args.length < 2) {
      if (sender.hasPermission("boosty.admin.*")) {
        sender.sendMessage(msg.getMessage("help_menu_admin"));
      } else {
        sender.sendMessage(msg.getMessage("no_permission"));
      }
      return;
    }

    String action = args[1].toLowerCase();
    String requiredPermission = action.equals("reload")
      ? "boosty.reload"
      : "boosty.admin." + action;

    if (!sender.hasPermission(requiredPermission)) {
      sender.sendMessage(msg.getMessage("no_permission"));
      return;
    }

    if (
      action.equals("setdiscord") &&
      !client.getPlugin().getConfig().getBoolean("discord.bot.enabled", false)
    ) {
      sender.sendMessage(msg.getMessage("unknown_command"));
      return;
    }

    switch (action) {
      case "setdiscord" -> handleSetDiscord(sender, args, msg);
      case "unlink" -> handleUnlink(sender, args, msg);
      case "info" -> handleInfo(sender, args, msg);
      case "forcelink" -> handleForceLink(sender, args, msg);
      case "forcesync" -> handleForceSync(sender, msg);
      default -> sender.sendMessage(msg.getMessage("unknown_command"));
    }
  }

  private void handleSetDiscord(
    CommandSender sender,
    String[] args,
    MessageManager msg
  ) {
    if (args.length < 4) {
      sender.sendMessage(msg.getMessage("admin_usage_setdiscord"));
      return;
    }

    String discordTag = args[3].trim();

    resolveTarget(sender, args[2], false, msg, target -> {
      BoostyUser user = client.getDatabase().getUser(target.uuid());
      String currentLevel = (user != null) ? user.levelName() : "none";
      String boostyName = (user != null) ? user.boostyName() : "";

      BoostyBridge plugin = (BoostyBridge) client.getPlugin();
      plugin
        .getDiscordBotManager()
        .updateUserRole(target.uuid(), target.name(), discordTag, currentLevel)
        .thenAccept(success -> {
          if (success) {
            client
              .getDatabase()
              .saveLink(
                target.uuid(),
                target.name(),
                boostyName,
                currentLevel
              );
            sender.sendMessage(
              msg
                .getMessage("admin_setdiscord_success")
                .replace("%player%", target.name())
                .replace("%discord_tag%", discordTag)
                .replace("%discord%", discordTag)
            );
          } else {
            sender.sendMessage(
              msg
                .getMessage("discord_not_found")
                .replace("%discord_tag%", discordTag)
            );
          }
        });
    });
  }

  private void handleUnlink(
    CommandSender sender,
    String[] args,
    MessageManager msg
  ) {
    if (args.length < 3) {
      sender.sendMessage(msg.getMessage("admin_usage_unlink"));
      return;
    }

    resolveTarget(sender, args[2], true, msg, target -> {
      BoostyUser user = client.getDatabase().getUser(target.uuid());

      if (user == null) {
        sender.sendMessage(
          msg
            .getMessage("admin_player_not_found")
            .replace("%player%", target.name())
        );
        return;
      }

      client.getDatabase().removeLink(target.uuid());
  
      sender.sendMessage(
        msg
          .getMessage("admin_unlink_success")
          .replace("%player%", target.name())
      );

      Player online = Bukkit.getPlayer(target.uuid());
      if (online != null) {
        online.sendMessage(
          msg
            .getMessage("admin_notify_unlinked")
            .replace("%admin%", sender.getName())
        );
      }
    });
  }

  private void handleInfo(
    CommandSender sender,
    String[] args,
    MessageManager msg
  ) {
    if (args.length < 3) {
      sender.sendMessage(msg.getMessage("admin_usage_info"));
      return;
    }

    resolveTarget(sender, args[2], true, msg, target -> {
      BoostyUser user = client.getDatabase().getUser(target.uuid());

      if (user == null) {
        sender.sendMessage(
          msg
            .getMessage("admin_player_not_found")
            .replace("%player%", target.name())
        );
        return;
      }

      for (String line : msg.getMessageList("info_format")) {
        sender.sendMessage(
          line
            .replace("%player%", target.name())
            .replace("%boosty%", user.boostyName())
            .replace("%level%", user.levelName())
        );
      }
    });
  }

  private void handleForceLink(
    CommandSender sender,
    String[] args,
    MessageManager msg
  ) {
    if (args.length < 4) {
      sender.sendMessage(msg.getMessage("admin_usage_forcelink"));
      sender.sendMessage(
        msg.getMessage(
          "admin_forcelink_quote_hint",
          "&7\u0415\u0441\u043b\u0438 \u043d\u0438\u043a Boosty \u0441 \u043f\u0440\u043e\u0431\u0435\u043b\u0430\u043c\u0438, \u0432\u043e\u0437\u044c\u043c\u0438\u0442\u0435 \u0435\u0433\u043e \u0432 \u043a\u0430\u0432\u044b\u0447\u043a\u0438: &f/boosty admin forcelink Player \"Boosty 1\""
        )
      );
      return;
    }

    ForceLinkArgs parsed = parseForceLinkArgs(args);

    if (parsed == null) {
      sender.sendMessage(msg.getMessage("admin_usage_forcelink"));
      sender.sendMessage(
        msg.getMessage(
          "admin_forcelink_quote_hint",
          "&7\u0415\u0441\u043b\u0438 \u043d\u0438\u043a Boosty \u0441 \u043f\u0440\u043e\u0431\u0435\u043b\u0430\u043c\u0438, \u0432\u043e\u0437\u044c\u043c\u0438\u0442\u0435 \u0435\u0433\u043e \u0432 \u043a\u0430\u0432\u044b\u0447\u043a\u0438: &f/boosty admin forcelink Player \"Boosty 1\""
        )
      );
      return;
    }

    final String boostyName = BoostyNameValidator.normalize(
      parsed.boostyName()
    );

    if (!BoostyNameValidator.isValid(boostyName)) {
      sender.sendMessage(
        msg.getMessage(
          "link_invalid_name",
          "&c\u041d\u0435\u0434\u043e\u043f\u0443\u0441\u0442\u0438\u043c\u043e\u0435 \u0438\u043c\u044f Boosty. \u0423\u0431\u0435\u0440\u0438\u0442\u0435 \u0441\u043b\u0443\u0436\u0435\u0431\u043d\u044b\u0435 \u0441\u0438\u043c\u0432\u043e\u043b\u044b (\u0434\u043e " +
          BoostyNameValidator.MAX_LENGTH +
          " \u0441\u0438\u043c\u0432\u043e\u043b\u043e\u0432)."
        )
      );
      return;
    }

    String requestedLevel = parsed.level();

    if (requestedLevel != null) {
      String canonical = matchRewardLevel(requestedLevel);
      if (canonical == null) {
        sender.sendMessage(
          msg
            .getMessage(
              "admin_forcelink_unknown_level",
              "&c\u0423\u0440\u043e\u0432\u0435\u043d\u044c &f%level% &c\u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d \u0432 \u0441\u0435\u043a\u0446\u0438\u0438 rewards: \u0432 config.yml. \u0414\u043e\u0441\u0442\u0443\u043f\u043d\u044b\u0435 \u0443\u0440\u043e\u0432\u043d\u0438: &f%levels%"
            )
            .replace("%level%", requestedLevel)
            .replace("%levels%", describeRewardLevels())
        );
        return;
      }
      requestedLevel = canonical;
    }

    final String level = requestedLevel;

    resolveTarget(sender, args[2], false, msg, target -> {
      if (level != null) {
        applyForceLink(sender, target, boostyName, level, msg, false);
        return;
      }

      sender.sendMessage(
        msg.getMessage(
          "admin_forcelink_detecting",
          "&7\u041e\u043f\u0440\u0435\u0434\u0435\u043b\u044f\u044e \u0443\u0440\u043e\u0432\u0435\u043d\u044c \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0438 \u043d\u0430 Boosty..."
        )
      );

      client
        .getBlogManager()
        .getSubscriberData(boostyName)
        .whenComplete((subData, ex) -> {
          if (ex != null) {
            client.debug(
              "forcelink: failed to fetch subscriber data: " + ex.getMessage()
            );
            sender.sendMessage(
              msg.getMessage(
                "admin_forcelink_detect_failed",
                "&e\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u043f\u043e\u043b\u0443\u0447\u0438\u0442\u044c \u0434\u0430\u043d\u043d\u044b\u0435 \u0441 Boosty. \u0423\u043a\u0430\u0436\u0438\u0442\u0435 \u0443\u0440\u043e\u0432\u0435\u043d\u044c \u0432\u0440\u0443\u0447\u043d\u0443\u044e: &f/boosty admin forcelink <\u0438\u0433\u0440\u043e\u043a> \"<\u043d\u0438\u043a>\" <\u0443\u0440\u043e\u0432\u0435\u043d\u044c>"
              )
            );
            return;
          }

          if (subData == null) {
            sender.sendMessage(
              msg
                .getMessage(
                  "admin_forcelink_not_subscriber",
                  "&e\u0410\u043a\u043a\u0430\u0443\u043d\u0442 &f%boosty% &e\u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d \u0432 \u0441\u043f\u0438\u0441\u043a\u0435 \u043f\u043e\u0434\u043f\u0438\u0441\u0447\u0438\u043a\u043e\u0432, \u043f\u0440\u0438\u0432\u044f\u0437\u044b\u0432\u0430\u044e \u0441 \u0443\u0440\u043e\u0432\u043d\u0435\u043c &fnone&e."
                )
                .replace("%boosty%", boostyName)
            );
            applyForceLink(sender, target, boostyName, "none", msg, false);
            return;
          }

          applyForceLink(
            sender,
            target,
            boostyName,
            extractLevel(subData),
            msg,
            true
          );
        });
    });
  }

  private void applyForceLink(
    CommandSender sender,
    Target target,
    String boostyName,
    String level,
    MessageManager msg,
    boolean detected
  ) {
    client
      .getDatabase()
      .saveLink(target.uuid(), target.name(), boostyName, level);

    sender.sendMessage(
      msg
        .getMessage("admin_forcelink_success")
        .replace("%boosty%", boostyName)
        .replace("%player%", target.name())
        .replace("%level%", level)
    );

    if (detected) {
      sender.sendMessage(
        msg
          .getMessage(
            "admin_forcelink_level_detected",
            "&7\u0423\u0440\u043e\u0432\u0435\u043d\u044c \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0438 \u043e\u043f\u0440\u0435\u0434\u0435\u043b\u0451\u043d \u0430\u0432\u0442\u043e\u043c\u0430\u0442\u0438\u0447\u0435\u0441\u043a\u0438: &f%level%"
          )
          .replace("%level%", level)
      );
    }

    if (matchRewardLevel(level) == null) {
      sender.sendMessage(
        msg
          .getMessage(
            "admin_forcelink_no_rewards",
            "&e\u0414\u043b\u044f \u0443\u0440\u043e\u0432\u043d\u044f &f%level% &e\u043d\u0435\u0442 \u043d\u0430\u0433\u0440\u0430\u0434 \u0432 config.yml (\u0441\u0435\u043a\u0446\u0438\u044f rewards:), \u043f\u043e\u044d\u0442\u043e\u043c\u0443 \u043d\u0438\u0447\u0435\u0433\u043e \u043d\u0435 \u0432\u044b\u0434\u0430\u043d\u043e. \u0414\u043e\u0441\u0442\u0443\u043f\u043d\u044b\u0435 \u0443\u0440\u043e\u0432\u043d\u0438: &f%levels%"
          )
          .replace("%level%", level)
          .replace("%levels%", describeRewardLevels())
      );
    } else {
      client
        .getRewardManager()
        .dispatch(
          target.uuid(),
          target.name(),
          boostyName,
          level,
          RewardManager.ACTION_GIVE
        );
    }

    ((BoostyBridge) client.getPlugin()).getDiscordBotManager()
      .syncRoleAndPrompt(target.uuid(), target.name(), null, level);

    client
      .getDiscordManager()
      .sendNotification("subscription", target.name(), boostyName, level);

    Player online = Bukkit.getPlayer(target.uuid());
    if (online != null) {
      online.sendMessage(
        msg
          .getMessage("admin_notify_forcelinked")
          .replace("%admin%", sender.getName())
          .replace("%boosty%", boostyName)
      );

      final String congratulationLevel = level;
      final String congratulationPlayer = target.name();
      Bukkit.getScheduler()
        .runTask(
          client.getPlugin(),
          () ->
            msg.broadcastCongratulation(
              congratulationPlayer,
              congratulationLevel
            )
        );
    }
  }

  private ForceLinkArgs parseForceLinkArgs(String[] args) {
    String raw = String.join(
      " ",
      Arrays.copyOfRange(args, 3, args.length)
    ).trim();

    if (raw.isEmpty()) return null;

    char first = raw.charAt(0);
    char closing = 0;
    if (first == QUOTE_DOUBLE) closing = QUOTE_DOUBLE;
    else if (first == QUOTE_SINGLE) closing = QUOTE_SINGLE;
    else if (first == QUOTE_RU_OPEN) closing = QUOTE_RU_CLOSE;

    if (closing != 0) {
      int end = raw.indexOf(closing, 1);
      if (end < 1) return null;

      String name = raw.substring(1, end).trim();
      if (name.isEmpty()) return null;

      String rest = stripQuotes(raw.substring(end + 1).trim());
      return new ForceLinkArgs(name, rest.isEmpty() ? null : rest);
    }

    String[] parts = raw.split("\\s+");

    for (int i = 1; i < parts.length; i++) {
      String candidate = stripQuotes(
        String.join(" ", Arrays.copyOfRange(parts, i, parts.length))
      );
      if (matchRewardLevel(candidate) != null) {
        String name = String.join(
          " ",
          Arrays.copyOfRange(parts, 0, i)
        ).trim();
        if (!name.isEmpty()) {
          return new ForceLinkArgs(name, candidate);
        }
      }
    }

    return new ForceLinkArgs(raw, null);
  }

  private String stripQuotes(String value) {
    String result = value == null ? "" : value.trim();
    if (result.length() < 2) return result;

    char first = result.charAt(0);
    char last = result.charAt(result.length() - 1);

    boolean quoted =
      (first == QUOTE_DOUBLE && last == QUOTE_DOUBLE) ||
      (first == QUOTE_SINGLE && last == QUOTE_SINGLE) ||
      (first == QUOTE_RU_OPEN && last == QUOTE_RU_CLOSE);

    return quoted ? result.substring(1, result.length() - 1).trim() : result;
  }

  private String extractLevel(JsonObject subData) {
    if (subData == null) return "none";
    if (subData.has("level") && !subData.get("level").isJsonNull()) {
      JsonObject level = subData.getAsJsonObject("level");
      if (level.has("name") && !level.get("name").isJsonNull()) {
        return level.get("name").getAsString();
      }
    }
    return "none";
  }

  private Set<String> rewardLevels() {
    ConfigurationSection section = client
      .getPlugin()
      .getConfig()
      .getConfigurationSection("rewards");
    if (section == null) return new LinkedHashSet<>();
    return new LinkedHashSet<>(section.getKeys(false));
  }

  private String matchRewardLevel(String level) {
    if (level == null || level.isEmpty()) return null;
    for (String key : rewardLevels()) {
      if (key.equalsIgnoreCase(level)) return key;
    }
    return null;
  }

  private String describeRewardLevels() {
    Set<String> levels = rewardLevels();
    return levels.isEmpty() ? "-" : String.join(", ", levels);
  }

  private void handleForceSync(CommandSender sender, MessageManager msg) {
    sender.sendMessage(msg.getMessage("admin_forcesync_started"));
    sender.sendMessage(msg.getMessage("admin_forcesync_async"));

    new SubscriptionSyncTask(client)
      .runTaskAsynchronously(client.getPlugin());
  }

  private void resolveTarget(
    CommandSender sender,
    String rawName,
    boolean requireLinked,
    MessageManager msg,
    Consumer<Target> action
  ) {
    Player online = Bukkit.getPlayerExact(rawName);
    if (online != null) {
      action.accept(new Target(online.getUniqueId(), online.getName()));
      return;
    }

    BoostyUser stored = client.getDatabase().getUserByPlayerName(rawName);
    if (stored != null) {
      action.accept(
        new Target(
          stored.uuid(),
          stored.playerName() != null ? stored.playerName() : rawName
        )
      );
      return;
    }

    if (requireLinked) {
      sender.sendMessage(
        msg
          .getMessage("admin_player_not_found")
          .replace("%player%", rawName)
      );
      return;
    }

    sender.sendMessage(
      msg.getMessage(
        "admin_resolving_player",
        "&7\u0418\u0433\u0440\u043e\u043a \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d \u0432 \u0431\u0430\u0437\u0435, \u0432\u044b\u043f\u043e\u043b\u043d\u044f\u044e \u0430\u0441\u0438\u043d\u0445\u0440\u043e\u043d\u043d\u044b\u0439 \u043f\u043e\u0438\u0441\u043a UUID..."
      )
    );

    Bukkit.getScheduler().runTaskAsynchronously(client.getPlugin(), () -> {
      @SuppressWarnings("deprecation")
      OfflinePlayer offline = Bukkit.getOfflinePlayer(rawName);
      UUID uuid = offline.getUniqueId();
      String resolvedName = offline.getName() != null
        ? offline.getName()
        : rawName;

      Bukkit.getScheduler().runTask(client.getPlugin(), () -> {
        if (uuid == null) {
          sender.sendMessage(
            msg
              .getMessage("admin_player_not_found")
              .replace("%player%", resolvedName)
          );
          return;
        }
        action.accept(new Target(uuid, resolvedName));
      });
    });
  }
}
