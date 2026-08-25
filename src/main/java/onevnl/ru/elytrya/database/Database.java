package onevnl.ru.elytrya.database;

import java.util.List;
import java.util.UUID;
import onevnl.ru.elytrya.models.BoostyUser;
import onevnl.ru.elytrya.models.QueuedReward;

public interface Database {
  void connect();
  void disconnect();
  void saveLink(
    UUID uuid,
    String playerName,
    String boostyName,
    String levelName
  );
  boolean claimLink(
    UUID uuid,
    String playerName,
    String boostyName,
    String levelName
  );
  String getBoostyName(UUID uuid);
  boolean isBoostyNameLinked(String boostyName);
  BoostyUser getUserByBoostyName(String boostyName);
  List<BoostyUser> getAllUsers();
  void removeLink(UUID uuid);
  void updateLevel(UUID uuid, String levelName);
  int getActiveSubscribersCount();
  BoostyUser getUser(UUID uuid);
  BoostyUser getUserByPlayerName(String playerName);

  //для дискорд бота
  String getDiscordUser(UUID uuid);
  void setDiscordUser(UUID uuid, String discordUser);

  void queueReward(
    UUID uuid,
    String action,
    String levelName,
    String boostyName
  );
  List<QueuedReward> getQueuedRewards(UUID uuid);
  void clearQueuedRewards(UUID uuid);
  int getQueuedRewardsCount();
}
