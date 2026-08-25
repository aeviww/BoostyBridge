package onevnl.ru.elytrya.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import onevnl.ru.elytrya.models.BoostyUser;
import onevnl.ru.elytrya.models.QueuedReward;
import onevnl.ru.elytrya.util.BoostyNameValidator;
import org.bukkit.plugin.java.JavaPlugin;

public abstract class AbstractDatabase implements Database {

  protected final JavaPlugin plugin;
  protected Connection connection;
  protected final Object lock = new Object();

  protected AbstractDatabase(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  protected void ensureConnection() {
    try {
      if (connection != null && !connection.isClosed() && connection.isValid(2)) {
        return;
      }
    } catch (SQLException ignored) {}

    plugin.getLogger().warning("Database connection lost, reconnecting...");
    try {
      if (connection != null) connection.close();
    } catch (SQLException ignored) {}
    connection = null;
    connect();
  }

  @Override
  public void disconnect() {
    try {
      if (connection != null && !connection.isClosed()) connection.close();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  protected void createTable() {
    String sql =
      "CREATE TABLE IF NOT EXISTS boosty_links (uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(16), boosty_name VARCHAR(255), level_name VARCHAR(255))";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    } catch (SQLException e) {
      e.printStackTrace();
    }

    //если колонки discord_user нет
    try {
      String alterSql =
        "ALTER TABLE boosty_links ADD COLUMN discord_user VARCHAR(255) DEFAULT NULL";
      try (
        PreparedStatement statement = connection.prepareStatement(alterSql)
      ) {
        statement.execute();
      }
    } catch (SQLException ignored) {}

    createRewardQueueTable();
    createIndexes();
  }

  protected void createIndexes() {
    String[] statements = new String[] {
      "CREATE INDEX idx_boosty_queue_uuid ON boosty_reward_queue (uuid)",
      "CREATE INDEX idx_boosty_links_player ON boosty_links (player_name)"
    };
    for (String sql : statements) {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.execute();
      } catch (SQLException ignored) {}
    }
  }

  protected void createRewardQueueTable() {
    String sql =
      "CREATE TABLE IF NOT EXISTS boosty_reward_queue (uuid VARCHAR(36), action VARCHAR(16), level_name VARCHAR(255), boosty_name VARCHAR(255), created_at BIGINT)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  @Override
  public synchronized void queueReward(
    UUID uuid,
    String action,
    String levelName,
    String boostyName
  ) {
    ensureConnection();
    String sql =
      "INSERT INTO boosty_reward_queue (uuid, action, level_name, boosty_name, created_at) VALUES (?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, uuid.toString());
      statement.setString(2, action);
      statement.setString(3, levelName);
      statement.setString(4, boostyName);
      statement.setLong(5, System.currentTimeMillis());
      statement.executeUpdate();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  @Override
  public synchronized List<QueuedReward> getQueuedRewards(UUID uuid) {
    ensureConnection();
    List<QueuedReward> list = new ArrayList<>();
    String sql =
      "SELECT action, level_name, boosty_name, created_at FROM boosty_reward_queue WHERE uuid = ? ORDER BY created_at ASC";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, uuid.toString());
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          list.add(
            new QueuedReward(
              uuid,
              rs.getString("action"),
              rs.getString("level_name"),
              rs.getString("boosty_name"),
              rs.getLong("created_at")
            )
          );
        }
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return list;
  }

  @Override
  public synchronized void clearQueuedRewards(UUID uuid) {
    ensureConnection();
    String sql = "DELETE FROM boosty_reward_queue WHERE uuid = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, uuid.toString());
      statement.executeUpdate();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  @Override
  public synchronized int getQueuedRewardsCount() {
    ensureConnection();
    String sql = "SELECT COUNT(*) FROM boosty_reward_queue";
    try (
      PreparedStatement statement = connection.prepareStatement(sql);
      ResultSet rs = statement.executeQuery()
    ) {
      if (rs.next()) return rs.getInt(1);
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return 0;
  }

  @Override
  public synchronized BoostyUser getUser(UUID uuid) {
    ensureConnection();
    String sql = "SELECT * FROM boosty_links WHERE uuid = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, uuid.toString());
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) return mapUser(rs);
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public synchronized BoostyUser getUserByPlayerName(String playerName) {
    ensureConnection();
    if (playerName == null || playerName.isEmpty()) return null;
    String sql = "SELECT * FROM boosty_links WHERE LOWER(player_name) = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, playerName.toLowerCase());
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) return mapUser(rs);
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public synchronized String getBoostyName(UUID uuid) {
    ensureConnection();
    BoostyUser user = getUser(uuid);
    return user != null ? user.boostyName() : null;
  }

  @Override
  public synchronized boolean isBoostyNameLinked(String boostyName) {
    return getUserByBoostyName(boostyName) != null;
  }

  @Override
  public synchronized BoostyUser getUserByBoostyName(String boostyName) {
    ensureConnection();
    String key = BoostyNameValidator.key(boostyName);
    if (key.isEmpty()) return null;

    String sql = "SELECT * FROM boosty_links WHERE boosty_name IS NOT NULL";
    try (
      PreparedStatement statement = connection.prepareStatement(sql);
      ResultSet rs = statement.executeQuery()
    ) {
      while (rs.next()) {
        BoostyUser user = mapUser(rs);
        if (BoostyNameValidator.key(user.boostyName()).equals(key)) {
          return user;
        }
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public synchronized boolean claimLink(
    UUID uuid,
    String playerName,
    String boostyName,
    String levelName
  ) {
    ensureConnection();
    BoostyUser owner = getUserByBoostyName(boostyName);
    if (owner != null && !owner.uuid().equals(uuid)) return false;

    saveLink(uuid, playerName, boostyName, levelName);
    return true;
  }

  @Override
  public synchronized List<BoostyUser> getAllUsers() {
    ensureConnection();
    List<BoostyUser> list = new ArrayList<>();
    String sql = "SELECT * FROM boosty_links";
    try (
      PreparedStatement statement = connection.prepareStatement(sql);
      ResultSet rs = statement.executeQuery()
    ) {
      while (rs.next()) list.add(mapUser(rs));
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return list;
  }

  @Override
  public synchronized void removeLink(UUID uuid) {
    ensureConnection();
    String sql = "DELETE FROM boosty_links WHERE uuid = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, uuid.toString());
      statement.executeUpdate();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  @Override
  public synchronized void updateLevel(UUID uuid, String levelName) {
    ensureConnection();
    String sql = "UPDATE boosty_links SET level_name = ? WHERE uuid = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, levelName);
      statement.setString(2, uuid.toString());
      statement.executeUpdate();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  @Override
  public synchronized int getActiveSubscribersCount() {
    ensureConnection();
    String sql = "SELECT COUNT(*) FROM boosty_links WHERE level_name != 'none'";
    try (
      PreparedStatement statement = connection.prepareStatement(sql);
      ResultSet rs = statement.executeQuery()
    ) {
      if (rs.next()) return rs.getInt(1);
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return 0;
  }

  @Override
  public synchronized String getDiscordUser(UUID uuid) {
    ensureConnection();
    String sql = "SELECT discord_user FROM boosty_links WHERE uuid = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, uuid.toString());
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) return rs.getString("discord_user");
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public synchronized void setDiscordUser(UUID uuid, String discordUser) {
    ensureConnection();
    String checkSql = "SELECT 1 FROM boosty_links WHERE uuid = ?";
    boolean exists = false;
    try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
      checkStmt.setString(1, uuid.toString());
      try (ResultSet rs = checkStmt.executeQuery()) {
        if (rs.next()) exists = true;
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }

    if (exists) {
      String sql = "UPDATE boosty_links SET discord_user = ? WHERE uuid = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, discordUser);
        statement.setString(2, uuid.toString());
        statement.executeUpdate();
      } catch (SQLException e) {
        e.printStackTrace();
      }
    } else {
      org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(uuid);
      String name = online != null ? online.getName() : "Unknown";
      String sql =
        "INSERT INTO boosty_links (uuid, player_name, boosty_name, level_name, discord_user) VALUES (?, ?, 'none', 'none', ?)";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, uuid.toString());
        statement.setString(2, name);
        statement.setString(3, discordUser);
        statement.executeUpdate();
      } catch (SQLException e) {
        e.printStackTrace();
      }
    }
  }

  protected BoostyUser mapUser(ResultSet rs) throws SQLException {
    return new BoostyUser(
      UUID.fromString(rs.getString("uuid")),
      rs.getString("player_name"),
      rs.getString("boosty_name"),
      rs.getString("level_name")
    );
  }
}
