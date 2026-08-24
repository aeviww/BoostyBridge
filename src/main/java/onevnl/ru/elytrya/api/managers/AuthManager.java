package onevnl.ru.elytrya.api.managers;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.util.TokenCipher;
import org.bukkit.configuration.file.FileConfiguration;

public class AuthManager {

  private final BoostyClient client;

  private String accessToken;
  private String refreshToken;
  private String clientId;
  private long expiresAt;

  public AuthManager(BoostyClient client) {
    this.client = client;
    loadAuth();
  }

  private void loadAuth() {
    FileConfiguration config = client.getPlugin().getConfig();
    String authData = config.getString("auth.auth_data", "");
    this.clientId = config.getString("auth.client_id", "");

    if (authData == null || authData.isEmpty() || authData.contains("вставьте")) {
      return;
    }

    boolean wasEncrypted = TokenCipher.isEncrypted(authData);

    try {
      String payload = authData;

      if (wasEncrypted) {
        TokenCipher cipher = client.getTokenCipher();
        if (cipher == null) {
          client
            .getPlugin()
            .getLogger()
            .severe(
              "auth_data is encrypted but secret.key is unavailable. Authorization cannot be restored."
            );
          return;
        }
        payload = cipher.decrypt(authData);
      }

      String decoded = URLDecoder.decode(payload, StandardCharsets.UTF_8);
      JsonObject data = client.getGson().fromJson(decoded, JsonObject.class);

      if (data.has("accessToken")) {
        this.accessToken = data.get("accessToken").getAsString();
      }
      if (data.has("refreshToken")) {
        this.refreshToken = data.get("refreshToken").getAsString();
      }
      if (data.has("expiresAt")) {
        this.expiresAt = data.get("expiresAt").getAsLong();
      }

      if (!wasEncrypted && client.getTokenCipher() != null) {
        client
          .getPlugin()
          .getLogger()
          .info(
            "Migrating plain auth_data to encrypted storage (see secret.key in the plugin folder)."
          );
        saveAuth();
      }
    } catch (Exception e) {
      client
        .getPlugin()
        .getLogger()
        .severe("Failed to parse auth_data: " + e.getClass().getSimpleName());
    }
  }

  private void saveAuth() {
    JsonObject data = new JsonObject();
    data.addProperty("accessToken", this.accessToken);
    data.addProperty("refreshToken", this.refreshToken);
    data.addProperty("expiresAt", this.expiresAt);
    data.addProperty("isNewUser", false);

    String jsonString = client.getGson().toJson(data);
    String encoded = java.net.URLEncoder.encode(
      jsonString,
      StandardCharsets.UTF_8
    );

    String stored = encoded;
    TokenCipher cipher = client.getTokenCipher();
    if (cipher != null) {
      try {
        stored = cipher.encrypt(encoded);
      } catch (Exception e) {
        client
          .getPlugin()
          .getLogger()
          .severe(
            "Failed to encrypt auth_data, storing it unencrypted: " +
            e.getClass().getSimpleName()
          );
      }
    }

    FileConfiguration config = client.getPlugin().getConfig();
    config.set("auth.auth_data", stored);
    config.set("auth.client_id", this.clientId);
    client.getPlugin().saveConfig();
  }

  public CompletableFuture<Boolean> checkAndRefreshToken() {
    if (accessToken == null || accessToken.isEmpty()) {
      client
        .getPlugin()
        .getLogger()
        .severe("Token is missing! Please specify auth_data in config.yml!");
      return CompletableFuture.completedFuture(false);
    }

    long nowMs = System.currentTimeMillis();
    if (expiresAt - nowMs > 21600000L) {
      return CompletableFuture.completedFuture(true);
    }

    if (clientId == null || clientId.isEmpty() || clientId.contains("вставьте")) {
      client
        .getPlugin()
        .getLogger()
        .severe("Invalid Client ID! Please specify client_id in config.yml!");
      return CompletableFuture.completedFuture(false);
    }

    String payload =
      "device_id=" +
      clientId +
      "&device_os=web" +
      "&grant_type=refresh_token" +
      "&refresh_token=" +
      refreshToken;

    try {
      HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.boosty.to/oauth/token/"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Authorization", "Bearer " + accessToken)
        .header("User-Agent", "Mozilla/5.0")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

      return client
        .getHttpClient()
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> {
          if (response.statusCode() == 200) {
            JsonObject data = client
              .getGson()
              .fromJson(response.body(), JsonObject.class);
            this.accessToken = data.get("access_token").getAsString();
            this.refreshToken = data.get("refresh_token").getAsString();
            this.expiresAt = System.currentTimeMillis() + 604800000L;
            saveAuth();
            return true;
          } else if (
            response.statusCode() == 401 || response.statusCode() == 403
          ) {
            client
              .getPlugin()
              .getLogger()
              .severe(
                "Boosty token is invalid (401/403 error)! Please update auth_data in config.yml!"
              );
            return false;
          } else {
            client
              .getPlugin()
              .getLogger()
              .warning(
                "Failed to refresh token. Response code: " +
                response.statusCode()
              );
            return expiresAt - System.currentTimeMillis() > 0;
          }
        })
        .exceptionally(ex -> {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          client
            .getPlugin()
            .getLogger()
            .warning(
              "Network error while refreshing the Boosty token: " +
              cause.getClass().getSimpleName()
            );
          return expiresAt - System.currentTimeMillis() > 0;
        });
    } catch (IllegalArgumentException e) {
      client
        .getPlugin()
        .getLogger()
        .severe(
          "Invalid characters in token! Check config.yml. Error: " +
          e.getMessage()
        );
      return CompletableFuture.completedFuture(false);
    } catch (Exception e) {
      client
        .getPlugin()
        .getLogger()
        .severe(
          "Unknown error while refreshing token: " +
          e.getClass().getSimpleName()
        );
      return CompletableFuture.completedFuture(false);
    }
  }

  public String getAccessToken() {
    return accessToken;
  }

  public String getRefreshToken() {
    return refreshToken;
  }

  public String getClientId() {
    return clientId;
  }

  public long getExpiresAt() {
    return expiresAt;
  }
}
