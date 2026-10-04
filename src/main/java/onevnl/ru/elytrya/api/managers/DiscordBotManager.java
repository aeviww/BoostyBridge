package onevnl.ru.elytrya.api.managers;

import java.awt.Color;
import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.models.PendingDiscordConfirm;
import onevnl.ru.elytrya.util.ClickableMessages;
import onevnl.ru.elytrya.util.DiscordTextSanitizer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import onevnl.ru.elytrya.util.RewardLevels;

public class DiscordBotManager {

  public enum VerificationStatus {
    SENT,
    MEMBER_NOT_FOUND,
    DM_FAILED,
    UNAVAILABLE,
  }

  public record VerificationRequest(
    VerificationStatus status,
    String memberId,
    String memberName
  ) {}

  private static final String DEFAULT_VERIFICATION_TEMPLATE =
    "\u041a\u043e\u0434 \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0436\u0434\u0435\u043d\u0438\u044f \u043f\u0440\u0438\u0432\u044f\u0437\u043a\u0438 Discord: **{code}**\n" +
    "\u0418\u0433\u0440\u043e\u043a \u043d\u0430 \u0441\u0435\u0440\u0432\u0435\u0440\u0435: **{player}**\n\n" +
    "\u0412\u0432\u0435\u0434\u0438\u0442\u0435 \u044d\u0442\u043e\u0442 \u043a\u043e\u0434 \u0432 \u0447\u0430\u0442 Minecraft-\u0441\u0435\u0440\u0432\u0435\u0440\u0430, \u0447\u0442\u043e\u0431\u044b \u0437\u0430\u0432\u0435\u0440\u0448\u0438\u0442\u044c \u043f\u0440\u0438\u0432\u044f\u0437\u043a\u0443.\n" +
    "\u0415\u0441\u043b\u0438 \u0432\u044b \u043d\u0435 \u0437\u0430\u043f\u0440\u0430\u0448\u0438\u0432\u0430\u043b\u0438 \u043f\u0440\u0438\u0432\u044f\u0437\u043a\u0443 - \u043f\u0440\u043e\u0441\u0442\u043e \u043f\u0440\u043e\u0438\u0433\u043d\u043e\u0440\u0438\u0440\u0443\u0439\u0442\u0435 \u044d\u0442\u043e \u0441\u043e\u043e\u0431\u0449\u0435\u043d\u0438\u0435.";

  private final BoostyClient client;
  private JDA jda;
  private boolean enabled;
  private boolean verificationEnabled;
  private String verificationTemplate;
  private long guildId;
  private long channelId;

  public DiscordBotManager(BoostyClient client) {
    this.client = client;
    reload();
  }

  public void reload() {
    FileConfiguration config = client.getPlugin().getConfig();
    this.enabled = config.getBoolean("discord.bot.enabled", false);
    this.verificationEnabled = config.getBoolean(
      "discord.bot.verification.enabled",
      true
    );
    this.verificationTemplate = config.getString(
      "discord.bot.verification.message_template",
      DEFAULT_VERIFICATION_TEMPLATE
    );

    if (!enabled) {
      stop();
      return;
    }

    this.guildId = config.getLong("discord.bot.guild_id", 0);
    this.channelId = config.getLong("discord.bot.channel_id", 0);
    String token = config.getString("discord.bot.token", "");

    if (
      token == null || token.isEmpty() || token.equals("YOUR_BOT_TOKEN_HERE")
    ) {
      this.enabled = false;
      return;
    }

    if (jda == null) {
      try {
        this.jda = JDABuilder.createLight(
          token,
          EnumSet.of(GatewayIntent.GUILD_MEMBERS)
        )
          .build()
          .awaitReady();
      } catch (Exception e) {
        client
          .getPlugin()
          .getLogger()
          .severe("Failed to start Discord bot: " + e.getMessage());
        this.enabled = false;
      }
    }
  }

  public void shutdown() {
    stop();
  }

  public void stop() {
    if (jda == null) return;

    net.dv8tion.jda.api.JDA current = jda;
    jda = null;

    try {
      current.shutdown();
    } catch (Exception e) {
      client
        .getPlugin()
        .getLogger()
        .warning("Failed to stop Discord bot gracefully: " + e.getClass().getSimpleName());
    }
  }

  public boolean isEnabled() {
    return enabled && jda != null;
  }

  public boolean isVerificationEnabled() {
    return verificationEnabled;
  }

  public boolean hasRoleForLevel(String levelName) {
    if (levelName == null || levelName.isEmpty()) return false;
    return getRoleIdForLevel(levelName) != 0;
  }

  public void sendConfirmPrompt(UUID uuid, boolean withIntro) {
    PendingDiscordConfirm pending = client.getPendingDiscordConfirms().get(uuid);
    if (pending == null || pending.isExpired()) return;

    Player player = Bukkit.getPlayer(uuid);
    if (player == null || !player.isOnline()) return;

    MessageManager msg = client.getMessageManager();

    if (withIntro) {
      player.sendMessage(
        msg
          .getMessage(
            "discord_auto_linked",
            "&f\u041d\u0430\u0448\u0451\u043b \u0432\u0430\u0448 Discord: &#FFB6C1%discord_tag%&f - \u0440\u043e\u043b\u044c \u0432\u044b\u0434\u0430\u043d\u0430."
          )
          .replace("%discord_tag%", pending.discordName())
      );
    }

    String question = msg
      .getMessage(
        "discord_confirm_question",
        "&f\u042d\u0442\u043e \u0442\u043e\u0447\u043d\u043e \u0432\u0430\u0448 \u0430\u043a\u043a\u0430\u0443\u043d\u0442? "
      )
      .replace("%discord_tag%", pending.discordName());

    String yesLabel = msg.getRawMessage(
      "discord_confirm_yes",
      "&a&l[\u2714 \u0414\u0430]"
    );
    String yesHover = msg
      .getRawMessage(
        "discord_confirm_yes_hover",
        "&f\u041f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u0442\u044c: &#FFB6C1%discord_tag%\n&7\u041d\u0430\u0436\u043c\u0438\u0442\u0435, \u0447\u0442\u043e\u0431\u044b \u043e\u0441\u0442\u0430\u0432\u0438\u0442\u044c \u044d\u0442\u043e\u0442 \u0430\u043a\u043a\u0430\u0443\u043d\u0442"
      )
      .replace("%discord_tag%", pending.discordName());

    String noLabel = msg.getRawMessage(
      "discord_confirm_no",
      "&c&l[\u2716 \u041d\u0435\u0442]"
    );
    String noHover = msg.getRawMessage(
      "discord_confirm_no_hover",
      "&f\u042d\u0442\u043e \u043d\u0435 \u043c\u043e\u0439 \u0430\u043a\u043a\u0430\u0443\u043d\u0442\n&7\u0420\u043e\u043b\u044c \u0431\u0443\u0434\u0435\u0442 \u0441\u043d\u044f\u0442\u0430, \u043f\u0440\u0438\u0432\u044f\u0437\u043a\u0430 \u043e\u0442\u043c\u0435\u043d\u0435\u043d\u0430"
    );

    ClickableMessages.send(
      player,
      ClickableMessages.text(question),
      ClickableMessages.runButton(yesLabel, yesHover, "/boosty discord confirm"),
      ClickableMessages.text(" "),
      ClickableMessages.runButton(noLabel, noHover, "/boosty discord reject")
    );
  }

  public void sendChangePrompt(UUID uuid) {
    Player player = Bukkit.getPlayer(uuid);
    if (player == null || !player.isOnline()) return;

    MessageManager msg = client.getMessageManager();

    String label = msg.getRawMessage(
      "discord_change_button",
      "&#FFB6C1&l[\u270e \u0423\u043a\u0430\u0437\u0430\u0442\u044c \u0441\u0432\u043e\u0439 Discord]"
    );
    String hover = msg.getRawMessage(
      "discord_change_hover",
      "&7\u041d\u0430\u0436\u043c\u0438\u0442\u0435 \u0438 \u0434\u043e\u043f\u0438\u0448\u0438\u0442\u0435 \u0441\u0432\u043e\u0439 \u044e\u0437\u0435\u0440\u043d\u0435\u0439\u043c Discord"
    );

    ClickableMessages.send(
      player,
      ClickableMessages.suggestButton(label, hover, "/boosty discord ")
    );
  }

  public void promptDiscordLink(
    UUID uuid,
    String playerName,
    String levelName
  ) {
    if (!isEnabled()) return;
    if (!hasRoleForLevel(levelName)) return;

    String linked = client.getDatabase().getDiscordUser(uuid);
    if (linked != null && !linked.isEmpty()) return;

    MessageManager msg = client.getMessageManager();
    String text = msg
      .getMessage(
        "discord_prompt_link",
        "&f\u0417\u0430 \u0443\u0440\u043e\u0432\u0435\u043d\u044c &#FFB6C1%level% &f\u0432\u044b\u0434\u0430\u0451\u0442\u0441\u044f \u0440\u043e\u043b\u044c \u0432 Discord. \u041f\u0440\u0438\u0432\u044f\u0436\u0438\u0442\u0435 Discord: &#FFB6C1/boosty discord <\u0432\u0430\u0448_\u044e\u0437\u0435\u0440\u043d\u0435\u0439\u043c>"
      )
      .replace("%level%", levelName)
      .replace("%player%", playerName);

    Bukkit.getScheduler().runTask(client.getPlugin(), () -> {
      Player player = Bukkit.getPlayer(uuid);
      if (player != null) {
        player.sendMessage(text);
        sendChangePrompt(uuid);
      }
    });
  }

  public CompletableFuture<Boolean> confirmDiscordLink(
    UUID uuid,
    String playerName
  ) {
    PendingDiscordConfirm pending = client
      .getPendingDiscordConfirms()
      .remove(uuid);
    if (pending == null || pending.isExpired()) {
      return CompletableFuture.completedFuture(false);
    }

    client.getDatabase().setDiscordUser(uuid, pending.discordName());

    return updateUserRole(
      uuid,
      playerName,
      pending.discordName(),
      pending.levelName(),
      false
    );
  }

  public CompletableFuture<Boolean> rejectDiscordLink(
    UUID uuid,
    String playerName
  ) {
    PendingDiscordConfirm pending = client
      .getPendingDiscordConfirms()
      .remove(uuid);
    if (pending == null) {
      return CompletableFuture.completedFuture(false);
    }

    client.getDatabase().setDiscordUser(uuid, null);

    return removeUserRole(uuid, playerName, pending.levelName())
      .thenApply(removed -> true)
      .exceptionally(ex -> true);
  }

  public CompletableFuture<VerificationRequest> requestVerification(
    String discordName,
    String playerName,
    String code
  ) {
    CompletableFuture<VerificationRequest> future = new CompletableFuture<>();

    if (!isEnabled()) {
      future.complete(
        new VerificationRequest(VerificationStatus.UNAVAILABLE, null, null)
      );
      return future;
    }

    Guild guild = jda.getGuildById(guildId);
    if (guild == null) {
      future.complete(
        new VerificationRequest(VerificationStatus.UNAVAILABLE, null, null)
      );
      return future;
    }

    findMember(guild, discordName).thenAccept(member -> {
      if (member == null) {
        future.complete(
          new VerificationRequest(
            VerificationStatus.MEMBER_NOT_FOUND,
            null,
            null
          )
        );
        return;
      }

      String memberId = member.getId();
      String memberName = member.getUser().getName();
      String text = verificationTemplate
        .replace("{code}", code)
        .replace("{player}", DiscordTextSanitizer.value(playerName))
        .replace("{discord}", DiscordTextSanitizer.value(memberName));

      member
        .getUser()
        .openPrivateChannel()
        .queue(
          channel ->
            channel
              .sendMessage(text)
              .queue(
                sent ->
                  future.complete(
                    new VerificationRequest(
                      VerificationStatus.SENT,
                      memberId,
                      memberName
                    )
                  ),
                error ->
                  future.complete(
                    new VerificationRequest(
                      VerificationStatus.DM_FAILED,
                      memberId,
                      memberName
                    )
                  )
              ),
          error ->
            future.complete(
              new VerificationRequest(
                VerificationStatus.DM_FAILED,
                memberId,
                memberName
              )
            )
        );
    });

    return future;
  }

  public CompletableFuture<Boolean> updateUserRole(
    UUID uuid,
    String playerName,
    String discordName,
    String boostyLevel
  ) {
    return updateUserRole(uuid, playerName, discordName, boostyLevel, false);
  }

  public CompletableFuture<Boolean> updateUserRole(
    UUID uuid,
    String playerName,
    String discordName,
    String boostyLevel,
    boolean requireConfirm
  ) {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    if (!enabled || jda == null) {
      future.complete(false);
      return future;
    }

    Guild guild = jda.getGuildById(guildId);
    if (guild == null) {
      future.complete(false);
      return future;
    }

    long roleId = getRoleIdForLevel(boostyLevel);
    if (roleId == 0) {
      future.complete(true);
      return future;
    }

    findMember(guild, discordName).thenAccept(member -> {
      if (member == null) {
        bidirectionalCheck(
          guild,
          uuid,
          playerName,
          roleId,
          boostyLevel,
          future,
          requireConfirm
        );
      } else {
        proceedWithRole(
          guild,
          member,
          roleId,
          uuid,
          playerName,
          boostyLevel,
          future,
          true,
          requireConfirm
        );
      }
    });
    return future;
  }

  private void bidirectionalCheck(
    Guild guild,
    UUID uuid,
    String playerName,
    long roleId,
    String boostyLevel,
    CompletableFuture<Boolean> future,
    boolean requireConfirm
  ) {
    findMember(guild, playerName).thenAccept(member -> {
      if (member != null) {
        proceedWithRole(
          guild,
          member,
          roleId,
          uuid,
          playerName,
          boostyLevel,
          future,
          true,
          requireConfirm
        );
      } else {
        future.complete(false);
      }
    });
  }

  public void syncRoleAndPrompt(
    UUID uuid,
    String playerName,
    String discordHint,
    String levelName
  ) {
    if (!isEnabled()) return;

    String stored = client.getDatabase().getDiscordUser(uuid);
    String lookupName;
    if (stored != null && !stored.isEmpty()) {
      lookupName = stored;
    } else if (discordHint != null && !discordHint.isEmpty()) {
      lookupName = discordHint;
    } else {
      lookupName = playerName;
    }

    if (!hasRoleForLevel(levelName)) {
      updateUserRole(uuid, playerName, lookupName, levelName, true);
      return;
    }

    updateUserRole(
      uuid,
      playerName,
      lookupName,
      levelName,
      true
    ).thenAccept(
      success -> {
        if (!success) {
          promptDiscordLink(uuid, playerName, levelName);
        }
      }
    );
  }

  public CompletableFuture<Boolean> removeUserRole(
    UUID uuid,
    String playerName,
    String boostyLevel
  ) {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    String discordName = client.getDatabase().getDiscordUser(uuid);
    if (discordName == null || discordName.isBlank()) {
      client.debug("Discord role removal skipped for " + playerName + ": no Discord account linked.");
      future.complete(true);
      return future;
    }
    if (!enabled || jda == null) {
      future.complete(false);
      return future;
    }

    Guild guild = jda.getGuildById(guildId);
    if (guild == null) {
      future.complete(false);
      return future;
    }

    long roleId = getRoleIdForLevel(boostyLevel);
    if (roleId == 0) {
      future.complete(true);
      return future;
    }

    String lookupName = discordName.trim();

    findMember(guild, lookupName).thenAccept(member -> {
      if (member == null) {
        future.complete(false);
      } else {
        proceedWithRole(
          guild,
          member,
          roleId,
          uuid,
          playerName,
          boostyLevel,
          future,
          false,
          false
        );
      }
    });
    return future;
  }

  private void proceedWithRole(
    Guild guild,
    Member member,
    long roleId,
    UUID uuid,
    String playerName,
    String boostyLevel,
    CompletableFuture<Boolean> future,
    boolean add,
    boolean requireConfirm
  ) {
    Role targetRole = guild.getRoleById(roleId);
    if (targetRole == null) {
      future.complete(false);
      return;
    }

    if (add) {
      if (requireConfirm && !hasStoredDiscord(uuid)) {
        registerConfirm(uuid, member, boostyLevel);
        future.complete(true);
        return;
      }

      guild
        .addRoleToMember(member, targetRole)
        .queue(
          s -> {
            rememberDiscordName(uuid, member);
            logEmbedToChannel(
              guild,
              "discord.role-given-embed",
              playerName,
              targetRole.getName(),
              boostyLevel,
              member.getAsMention()
            );
            future.complete(true);
          },
          f -> future.complete(false)
        );
    } else {
      guild
        .removeRoleFromMember(member, targetRole)
        .queue(
          s -> {
            logEmbedToChannel(
              guild,
              "discord.role-removed-embed",
              playerName,
              targetRole.getName(),
              boostyLevel,
              member.getAsMention()
            );
            future.complete(true);
          },
          f -> future.complete(false)
        );
    }
  }

  private void logEmbedToChannel(
    Guild guild,
    String configPath,
    String playerName,
    String roleName,
    String boostyLevel,
    String mention
  ) {
    if (channelId == 0) return;
    try {
      TextChannel channel = guild.getTextChannelById(channelId);
      if (channel == null) return;

      File file = new File(client.getPlugin().getDataFolder(), "messages.yml");
      FileConfiguration messagesConfig = YamlConfiguration.loadConfiguration(
        file
      );
      ConfigurationSection section = messagesConfig.getConfigurationSection(
        configPath
      );

      if (section == null) return;

      String safePlayer = DiscordTextSanitizer.value(playerName);
      String safeRole = DiscordTextSanitizer.value(roleName);
      String safeLevel = DiscordTextSanitizer.value(boostyLevel);
      String safeMention = DiscordTextSanitizer.mention(mention);

      EmbedBuilder embed = new EmbedBuilder();

      String colorStr = section.getString("color", "#FFB6C1");
      try {
        embed.setColor(Color.decode(colorStr));
      } catch (Exception e) {
        embed.setColor(Color.PINK);
      }

      String title = applyPlaceholders(
        section.getString("title", "\u041b\u043e\u0433 \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0438"),
        safePlayer,
        safeRole,
        safeLevel,
        safeMention
      );
      embed.setTitle(
        DiscordTextSanitizer.truncate(title, DiscordTextSanitizer.TITLE_LIMIT)
      );

      String description = applyPlaceholders(
        section.getString("description", ""),
        safePlayer,
        safeRole,
        safeLevel,
        safeMention
      );
      if (!description.isEmpty()) {
        embed.setDescription(
          DiscordTextSanitizer.truncate(
            description,
            DiscordTextSanitizer.DESCRIPTION_LIMIT
          )
        );
      }

      if (section.contains("fields")) {
        for (java.util.Map<?, ?> map : section.getMapList("fields")) {
          String fName = applyPlaceholders(
            String.valueOf(map.get("name")),
            safePlayer,
            safeRole,
            safeLevel,
            safeMention
          );
          String fValue = applyPlaceholders(
            String.valueOf(map.get("value")),
            safePlayer,
            safeRole,
            safeLevel,
            safeMention
          );
          boolean inline =
            map.containsKey("inline") && Boolean.TRUE.equals(map.get("inline"));

          embed.addField(
            DiscordTextSanitizer.truncate(
              fName,
              DiscordTextSanitizer.FIELD_NAME_LIMIT
            ),
            DiscordTextSanitizer.truncate(
              fValue,
              DiscordTextSanitizer.FIELD_VALUE_LIMIT
            ),
            inline
          );
        }
      }

      String footer = applyPlaceholders(
        section.getString("footer", ""),
        safePlayer,
        safeRole,
        safeLevel,
        safeMention
      );
      String safeFooter = DiscordTextSanitizer.footer(footer);
      if (!safeFooter.isEmpty()) {
        embed.setFooter(safeFooter);
      }

      channel.sendMessageEmbeds(embed.build()).queue();
    } catch (Exception e) {
      client
        .getPlugin()
        .getLogger()
        .warning(
          "[Discord Logs] \u041e\u0448\u0438\u0431\u043a\u0430 \u0441\u0431\u043e\u0440\u043a\u0438 Embed \u0441\u043e\u043e\u0431\u0449\u0435\u043d\u0438\u044f: " +
          e.getMessage()
        );
    }
  }

  private boolean hasStoredDiscord(UUID uuid) {
    if (uuid == null) return false;
    try {
      String stored = client.getDatabase().getDiscordUser(uuid);
      return stored != null && !stored.isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  private void registerConfirm(UUID uuid, Member member, String levelName) {
    if (uuid == null || member == null) return;

    String resolved = member.getUser().getName();
    if (resolved == null || resolved.isEmpty()) return;

    client
      .getPendingDiscordConfirms()
      .put(uuid, new PendingDiscordConfirm(resolved, levelName));
    client.debug("Found possible Discord account " + resolved + " for " + uuid);

    Bukkit.getScheduler()
      .runTask(client.getPlugin(), () -> sendConfirmPrompt(uuid, true));
  }

  private void rememberDiscordName(UUID uuid, Member member) {
    if (uuid == null || member == null) return;
    try {
      if (hasStoredDiscord(uuid)) return;

      String resolved = member.getUser().getName();
      if (resolved == null || resolved.isEmpty()) return;

      client.getDatabase().setDiscordUser(uuid, resolved);
      client.debug("Stored Discord account " + resolved + " for " + uuid);
    } catch (Exception e) {
      client
        .getPlugin()
        .getLogger()
        .warning(
          "Failed to store resolved Discord name: " +
          e.getClass().getSimpleName()
        );
    }
  }

  private String applyPlaceholders(
    String template,
    String playerName,
    String roleName,
    String boostyLevel,
    String mention
  ) {
    if (template == null) return "";
    return template
      .replace("{player}", playerName)
      .replace("{role_name}", roleName)
      .replace("{level_name}", boostyLevel)
      .replace("{ping}", mention)
      .replace("%player%", playerName)
      .replace("%role%", roleName)
      .replace("%role_name%", roleName)
      .replace("%level%", boostyLevel)
      .replace("%level_name%", boostyLevel)
      .replace("%mention%", mention)
      .replace("%ping%", mention);
  }

  private long getRoleIdForLevel(String level) {
    return RewardLevels.roleId(client.getPlugin().getConfig(), level);
  }

  private CompletableFuture<Member> findMember(Guild guild, String name) {
    CompletableFuture<Member> future = new CompletableFuture<>();
    if (name == null || name.isEmpty()) {
      future.complete(null);
      return future;
    }

    String lookup = name;
    int separator = lookup.indexOf('#');
    if (separator > 0) {
      lookup = lookup.substring(0, separator);
    }

    List<Member> cached = guild.getMembersByName(lookup, true);
    if (!cached.isEmpty()) {
      future.complete(cached.get(0));
      return future;
    }

    guild
      .retrieveMembersByPrefix(lookup, 5)
      .onSuccess(members -> {
        if (members != null && !members.isEmpty()) {
          future.complete(members.get(0));
        } else {
          future.complete(null);
        }
      })
      .onError(e -> future.complete(null));
    return future;
  }
}
