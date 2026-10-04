package onevnl.ru.elytrya.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class RewardLevels {

  public static final String REWARDS_SECTION = "rewards";
  public static final String ROLES_SECTION = "discord.bot.roles";

  private RewardLevels() {}

  public static Set<String> levels(FileConfiguration config) {
    ConfigurationSection section = section(config, REWARDS_SECTION);
    if (section == null) return new LinkedHashSet<>();
    return new LinkedHashSet<>(section.getKeys(false));
  }

  public static String describe(FileConfiguration config) {
    Set<String> levels = levels(config);
    return levels.isEmpty() ? "-" : String.join(", ", levels);
  }

  public static String match(FileConfiguration config, String level) {
    String target = key(level);
    if (target.isEmpty()) return null;
    for (String candidate : levels(config)) {
      if (key(candidate).equals(target)) return candidate;
    }
    if (isFollower(level)) {
      for (String candidate : levels(config)) {
        if (isFollower(candidate)) return candidate;
      }
    }
    return null;
  }

  public static boolean isFollower(String level) {
    String normalized = key(level);
    return normalized.equals("none") || normalized.equals("follower");
  }

  public static String canonical(FileConfiguration config, String level) {
    String matched = match(config, level);
    return matched != null ? matched : BoostyNameValidator.normalize(level);
  }

  public static List<String> commands(
    FileConfiguration config,
    String level,
    String action
  ) {
    ConfigurationSection levelSection = levelSection(config, level);
    if (levelSection == null) return Collections.emptyList();
    List<String> commands = levelSection.getStringList(action);
    return commands == null ? Collections.emptyList() : commands;
  }

  public static boolean congratulation(FileConfiguration config, String level) {
    ConfigurationSection levelSection = levelSection(config, level);
    return (
      levelSection != null && levelSection.getBoolean("congratulation", false)
    );
  }

  public static long roleId(FileConfiguration config, String level) {
    ConfigurationSection roles = section(config, ROLES_SECTION);
    String target = key(level);
    if (roles == null || target.isEmpty()) return 0L;

    String matched = null;
    for (String candidate : roles.getKeys(false)) {
      if (key(candidate).equals(target)) { matched = candidate; break; }
    }
    if (matched == null && isFollower(level)) {
      for (String candidate : roles.getKeys(false)) {
        if (isFollower(candidate)) { matched = candidate; break; }
      }
    }
    if (matched == null) return 0L;
    for (Map.Entry<String, Object> entry : roles.getValues(false).entrySet()) {
      if (!entry.getKey().equals(matched)) continue;

      Object value = entry.getValue();
      if (value instanceof ConfigurationSection roleSection) {
        return roleSection.getLong("role_id", 0L);
      }
      if (value instanceof Number number) {
        return number.longValue();
      }
      if (value instanceof String text) {
        try {
          return Long.parseLong(text.trim());
        } catch (NumberFormatException ignored) {
          return 0L;
        }
      }
      return 0L;
    }

    return 0L;
  }

  private static ConfigurationSection levelSection(
    FileConfiguration config,
    String level
  ) {
    ConfigurationSection rewards = section(config, REWARDS_SECTION);
    String matched = match(config, level);
    if (rewards == null || matched == null) return null;

    Object raw = rewards.getValues(false).get(matched);
    return raw instanceof ConfigurationSection matchedSection
      ? matchedSection
      : null;
  }

  private static ConfigurationSection section(
    FileConfiguration config,
    String path
  ) {
    return config == null ? null : config.getConfigurationSection(path);
  }

  private static String key(String value) {
    return BoostyNameValidator.normalize(value).toLowerCase(Locale.ROOT);
  }
}
