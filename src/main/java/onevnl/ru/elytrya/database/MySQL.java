package onevnl.ru.elytrya.database;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class MySQL extends AbstractDatabase {

  public MySQL(JavaPlugin plugin) {
    super(plugin);
  }

  @Override
  public void connect() {
    try {
      FileConfiguration config = plugin.getConfig();
      String host = config.getString("database.mysql.host", "localhost");
      int port = config.getInt("database.mysql.port", 3306);
      String database = config.getString("database.mysql.database", "boosty");
      String username = config.getString("database.mysql.username", "root");
      String password = config.getString("database.mysql.password", "");
      boolean useSsl = config.getBoolean("database.mysql.use_ssl", true);
      boolean verifyServerCertificate = config.getBoolean(
        "database.mysql.verify_server_certificate",
        true
      );

      StringBuilder url = new StringBuilder("jdbc:mysql://")
        .append(host)
        .append(":")
        .append(port)
        .append("/")
        .append(
          URLEncoder.encode(
            database != null ? database : "",
            StandardCharsets.UTF_8
          )
        )
        .append("?autoReconnect=true")
        .append("&useSSL=")
        .append(useSsl)
        .append("&requireSSL=")
        .append(useSsl)
        .append("&verifyServerCertificate=")
        .append(useSsl && verifyServerCertificate);

      if (!useSsl) {
        plugin
          .getLogger()
          .warning(
            "MySQL SSL is disabled (database.mysql.use_ssl: false). Traffic to the database is not encrypted."
          );
      } else if (!verifyServerCertificate) {
        plugin
          .getLogger()
          .warning(
            "MySQL server certificate verification is disabled (database.mysql.verify_server_certificate: false)."
          );
      }

      connection = DriverManager.getConnection(
        url.toString(),
        username,
        password
      );
      createTable();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void saveLink(
    UUID uuid,
    String playerName,
    String boostyName,
    String levelName
  ) {
    String sql =
      "INSERT INTO boosty_links (uuid, player_name, boosty_name, level_name) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_name = ?, boosty_name = ?, level_name = ?, subscription_status = 'active'";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, uuid.toString());
      statement.setString(2, playerName);
      statement.setString(3, boostyName);
      statement.setString(4, levelName);
      statement.setString(5, playerName);
      statement.setString(6, boostyName);
      statement.setString(7, levelName);
      statement.executeUpdate();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }
}
