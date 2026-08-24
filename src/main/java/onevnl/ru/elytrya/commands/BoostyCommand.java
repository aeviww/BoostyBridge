package onevnl.ru.elytrya.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import onevnl.ru.elytrya.BoostyBridge;
import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.api.managers.MessageManager;
import onevnl.ru.elytrya.commands.subcommands.*;
import onevnl.ru.elytrya.models.BoostyUser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class BoostyCommand implements CommandExecutor, TabCompleter {

  private final BoostyClient client;
  private final Map<String, SubCommand> subCommands = new HashMap<>();

  public BoostyCommand(BoostyClient client) {
    this.client = client;
    registerSubCommand(new ReloadSubCommand(client));
    registerSubCommand(new LinkSubCommand(client));
    registerSubCommand(new InfoSubCommand(client));
    registerSubCommand(new AdminSubCommand(client));
    registerSubCommand(new DiscordSubCommand(client));
  }

  private void registerSubCommand(SubCommand cmd) {
    subCommands.put(cmd.getName().toLowerCase(), cmd);
  }

  private boolean isDiscordEnabled() {
    BoostyBridge plugin = (BoostyBridge) Bukkit.getPluginManager().getPlugin(
      "BoostyBridge"
    );
    if (plugin != null) {
      return plugin.getConfig().getBoolean("discord.bot.enabled", false);
    }
    return client
      .getPlugin()
      .getConfig()
      .getBoolean("discord.bot.enabled", false);
  }

  @Override
  public boolean onCommand(
    CommandSender sender,
    Command command,
    String label,
    String[] args
  ) {
    MessageManager msg = client.getMessageManager();
    boolean discordEnabled = isDiscordEnabled();

    if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
      List<String> lines = msg.getMessageList("help_menu");
      if (lines != null) {
        for (String line : lines) {
          if (
            !discordEnabled &&
            (line.contains("/boosty discord") || line.contains("discord"))
          ) {
            continue;
          }
          sender.sendMessage(line);
        }
      }

      if (sender.hasPermission("boosty.admin.*")) {
        List<String> adminLines = msg.getMessageList("help_menu_admin");
        if (adminLines != null) {
          for (String line : adminLines) {
            sender.sendMessage(line);
          }
        }
      }
      return true;
    }

    String subName = args[0].toLowerCase();

    if (subName.equals("discord") && !discordEnabled) {
      sender.sendMessage(msg.getMessage("unknown_command"));
      return true;
    }

    SubCommand sub = subCommands.get(subName);

    if (sub == null) {
      sender.sendMessage(msg.getMessage("unknown_command"));
      return true;
    }

    if (
      sub.getPermission() != null && !sender.hasPermission(sub.getPermission())
    ) {
      sender.sendMessage(msg.getMessage("no_permission"));
      return true;
    }

    sub.execute(sender, args);
    return true;
  }

  @Override
  public List<String> onTabComplete(
    CommandSender sender,
    Command command,
    String alias,
    String[] args
  ) {
    List<String> completions = new ArrayList<>();
    boolean discordEnabled = isDiscordEnabled();

    if (args.length == 1) {
      String input = args[0].toLowerCase();
      subCommands
        .values()
        .stream()
        .filter(sub -> {
          if (sub.getName().equalsIgnoreCase("discord") && !discordEnabled) {
            return false;
          }
          return (
            sub.getPermission() == null ||
            sender.hasPermission(sub.getPermission())
          );
        })
        .map(SubCommand::getName)
        .filter(name -> name.startsWith(input))
        .forEach(completions::add);

      if ("help".startsWith(input)) {
        completions.add("help");
      }
    } else if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
      handleAdminTab(sender, completions, args, discordEnabled);
    } else if (
      args.length == 2 && args[0].equalsIgnoreCase("discord") && discordEnabled
    ) {
      if (sender instanceof Player player) {
        completions.add(player.getName());
      }
    }
    return completions;
  }

  private void handleAdminTab(
    CommandSender sender,
    List<String> list,
    String[] args,
    boolean discordEnabled
  ) {
    if (args.length == 2) {
      String input = args[1].toLowerCase();
      String[] adminActions = {
        "unlink",
        "info",
        "forcelink",
        "forcesync",
        "setdiscord",
      };

      for (String s : adminActions) {
        if (s.equals("setdiscord") && !discordEnabled) continue;

        if (s.startsWith(input) && sender.hasPermission("boosty.admin." + s)) {
          list.add(s);
        }
      }
    } else if (args.length == 3) {
      handlePlayerTab(sender, list, args, discordEnabled);
    } else if (
      args.length == 5 && args[1].equalsIgnoreCase("forcelink")
    ) {
      if (!sender.hasPermission("boosty.admin.forcelink")) return;

      org.bukkit.configuration.ConfigurationSection rewards = client
        .getPlugin()
        .getConfig()
        .getConfigurationSection("rewards");
      if (rewards == null) return;

      String input = args[4].toLowerCase();
      for (String level : rewards.getKeys(false)) {
        String suggestion = level.contains(" ")
          ? "\"" + level + "\""
          : level;
        if (
          level.toLowerCase().startsWith(input) ||
          suggestion.toLowerCase().startsWith(input)
        ) {
          list.add(suggestion);
        }
      }
    }
  }

  private void handlePlayerTab(
    CommandSender sender,
    List<String> list,
    String[] args,
    boolean discordEnabled
  ) {
    String sub = args[1].toLowerCase();
    String input = args[2].toLowerCase();

    if (
      sub.equals("unlink") ||
      sub.equals("info") ||
      sub.equals("setdiscord") ||
      sub.equals("forcelink")
    ) {
      if (sub.equals("setdiscord") && !discordEnabled) return;
      if (!sender.hasPermission("boosty.admin." + sub)) return;

      java.util.Set<String> names = new java.util.LinkedHashSet<>();

      if (sub.equals("forcelink")) {
        org.bukkit.Bukkit
          .getOnlinePlayers()
          .forEach(online -> names.add(online.getName()));
      }

      client
        .getDatabase()
        .getAllUsers()
        .stream()
        .map(BoostyUser::playerName)
        .filter(java.util.Objects::nonNull)
        .forEach(names::add);

      names
        .stream()
        .filter(n -> n.toLowerCase().startsWith(input))
        .forEach(list::add);
    }
  }
}
