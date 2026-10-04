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

    try (PreparedStatement probe = connection.prepareStatement("SELECT subscription_status FROM boosty_links WHERE 1 = 0")) {
      probe.executeQuery().close();
    } catch (SQLException missing) {
      try (PreparedStatement alter = connection.prepareStatement("ALTER TABLE boosty_links ADD COLUMN subscription_status VARCHAR(16) NOT NULL DEFAULT 'active'")) {
        alter.execute();
      } catch (SQLException e) {
        throw new IllegalStateException("Cannot migrate subscription status", e);
      }
    }
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
    String sql = "CREATE TABLE IF NOT EXISTS boosty_reward_queue (uuid VARCHAR(36), action VARCHAR(16), level_name VARCHAR(255), boosty_name VARCHAR(255), created_at BIGINT, reward_id VARCHAR(36), player_name VARCHAR(16))";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
      ensureQueueColumn("reward_id", "VARCHAR(36)");
      ensureQueueColumn("player_name", "VARCHAR(16)");
      migrateLegacyQueue();
      try (PreparedStatement index = connection.prepareStatement("CREATE UNIQUE INDEX idx_boosty_queue_reward_id ON boosty_reward_queue (reward_id)")) {
        index.execute();
      } catch (SQLException ignored) {}
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot initialize reward queue", e);
    }
  }

  private void ensureQueueColumn(String name, String type) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT " + name + " FROM boosty_reward_queue WHERE 1 = 0")) {
      statement.executeQuery().close();
      return;
    } catch (SQLException missingColumn) {
      try (PreparedStatement statement = connection.prepareStatement("ALTER TABLE boosty_reward_queue ADD COLUMN " + name + " " + type)) {
        statement.executeUpdate();
      }
    }
  }

  private void migrateLegacyQueue() throws SQLException {
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      List<QueuedReward> legacy = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, action, level_name, boosty_name, created_at, player_name FROM boosty_reward_queue WHERE reward_id IS NULL OR reward_id = ''"); ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          legacy.add(new QueuedReward(UUID.fromString(rs.getString("uuid")), rs.getString("action"), rs.getString("level_name"), rs.getString("boosty_name"), rs.getLong("created_at"), UUID.randomUUID().toString(), rs.getString("player_name")));
        }
      }
      if (!legacy.isEmpty()) {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM boosty_reward_queue WHERE reward_id IS NULL OR reward_id = ''")) {
          statement.executeUpdate();
        }
        for (QueuedReward reward : legacy) insertQueuedReward(reward);
      }
      connection.commit();
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }

  private void insertQueuedReward(QueuedReward reward) throws SQLException {
    String sql = "INSERT INTO boosty_reward_queue (uuid, action, level_name, boosty_name, created_at, reward_id, player_name) VALUES (?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, reward.uuid().toString());
      statement.setString(2, reward.action());
      statement.setString(3, reward.levelName());
      statement.setString(4, reward.boostyName());
      statement.setLong(5, reward.createdAt());
      statement.setString(6, reward.id());
      statement.setString(7, reward.playerName());
      statement.executeUpdate();
    }
  }

  @Override
  public synchronized void queueReward(UUID uuid, String action, String levelName, String boostyName) {
    queueReward(uuid, null, action, levelName, boostyName);
  }

  @Override
  public synchronized void queueReward(UUID uuid, String playerName, String action, String levelName, String boostyName) {
    ensureConnection();
    try {
      insertQueuedReward(new QueuedReward(uuid, action, levelName, boostyName, System.currentTimeMillis(), UUID.randomUUID().toString(), playerName));
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot enqueue reward", e);
    }
  }

  @Override
  public synchronized List<QueuedReward> getQueuedRewards(UUID uuid) {
    ensureConnection();
    List<QueuedReward> list = new ArrayList<>();
    String sql = "SELECT action, level_name, boosty_name, created_at, reward_id, player_name FROM boosty_reward_queue WHERE uuid = ? ORDER BY created_at ASC, CASE WHEN action = 'take' THEN 0 ELSE 1 END ASC, reward_id ASC";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, uuid.toString());
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          list.add(new QueuedReward(uuid, rs.getString("action"), rs.getString("level_name"), rs.getString("boosty_name"), rs.getLong("created_at"), rs.getString("reward_id"), rs.getString("player_name")));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read reward queue", e);
    }
    return list;
  }

  @Override
  public synchronized List<UUID> getQueuedRewardPlayers() {
    ensureConnection();
    List<UUID> players = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT uuid FROM boosty_reward_queue"); ResultSet rs = statement.executeQuery()) {
      while (rs.next()) players.add(UUID.fromString(rs.getString(1)));
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read queued players", e);
    }
    return players;
  }

  @Override
  public synchronized boolean deleteQueuedReward(String rewardId) {
    ensureConnection();
    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM boosty_reward_queue WHERE reward_id = ?")) {
      statement.setString(1, rewardId);
      return statement.executeUpdate() == 1;
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot acknowledge queued reward", e);
    }
  }

  @Override
  public synchronized void removeLinkAndClearRewards(UUID uuid) {
    ensureConnection();
    try {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM boosty_reward_queue WHERE uuid = ?")) {
          statement.setString(1, uuid.toString());
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM boosty_links WHERE uuid = ?")) {
          statement.setString(1, uuid.toString());
          statement.executeUpdate();
        }
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot unlink player and clear rewards", e);
    }
  }

  @Override
  public synchronized void deactivateAndClearRewards(UUID uuid) {
    ensureConnection();
    try {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement update = connection.prepareStatement("UPDATE boosty_links SET subscription_status = 'inactive' WHERE uuid = ?")) {
          update.setString(1, uuid.toString());
          if (update.executeUpdate() != 1) throw new SQLException("Linked player not found");
        }
        try (PreparedStatement clear = connection.prepareStatement("DELETE FROM boosty_reward_queue WHERE uuid = ?")) {
          clear.setString(1, uuid.toString());
          clear.executeUpdate();
        }
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally { connection.setAutoCommit(autoCommit); }
    } catch (SQLException e) { throw new IllegalStateException("Cannot deactivate linked subscription", e); }
  }

  @Override
  public synchronized void activateAndQueueReward(BoostyUser user, String levelName) {
    ensureConnection();
    try {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement update = connection.prepareStatement("UPDATE boosty_links SET level_name = ?, subscription_status = 'active' WHERE uuid = ? AND subscription_status = 'inactive' AND boosty_name = ?")) {
          update.setString(1, levelName);
          update.setString(2, user.uuid().toString());
          update.setString(3, user.boostyName());
          if (update.executeUpdate() != 1) throw new SQLException("Subscription already active or link changed");
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO boosty_reward_queue (uuid, action, level_name, boosty_name, created_at, reward_id, player_name) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
          insert.setString(1, user.uuid().toString());
          insert.setString(2, "give");
          insert.setString(3, levelName);
          insert.setString(4, user.boostyName());
          insert.setLong(5, System.currentTimeMillis());
          insert.setString(6, UUID.randomUUID().toString());
          insert.setString(7, user.playerName());
          insert.executeUpdate();
        }
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally { connection.setAutoCommit(autoCommit); }
    } catch (SQLException e) { throw new IllegalStateException("Cannot reactivate subscription and queue give", e); }
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
      throw new IllegalStateException("Cannot read linked player", e);
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
      throw new IllegalStateException("Cannot update/read subscription state", e);
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
      throw new IllegalStateException("Cannot update/read subscription state", e);
    }
  }

  @Override
  public synchronized int getActiveSubscribersCount() {
    ensureConnection();
    String sql = "SELECT COUNT(*) FROM boosty_links WHERE subscription_status = 'active'";
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
      throw new IllegalStateException("Cannot read Discord link", e);
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
      rs.getString("level_name"),
      rs.getString("subscription_status")
    );
  }
}
