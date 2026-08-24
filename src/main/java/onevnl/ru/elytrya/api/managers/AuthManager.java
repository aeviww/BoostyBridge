package onevnl.ru.elytrya.api.managers;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import onevnl.ru.elytrya.api.BoostyClient;
import org.bukkit.configuration.file.FileConfiguration;

public class AuthManager {

  public static final String USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

  private static final String TOKEN_URL = "https://api.boosty.to/oauth/token/";
  private static final long REFRESH_THRESHOLD_MS = 21600000L;
  private static final long DEFAULT_TOKEN_LIFETIME_MS = 604800000L;
  private static final long MIN_MILLIS_TIMESTAMP = 100000000000L;
  private static final int MAX_DECODE_ATTEMPTS = 4;

  private enum RefreshResult {
    SUCCESS,
    INVALID,
    TEMPORARY,
    RETRY_WITHOUT_BEARER,
  }

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

    if (authData.startsWith("ENC:")) {
      severe(
        "auth_data is still stored in the old encrypted format (ENC:...). Token encryption is removed, so paste the plain \"auth\" value from boosty.to into config.yml and delete secret.key."
      );
      return;
    }

    try {
      JsonObject data = client
        .getGson()
        .fromJson(decodePayload(authData), JsonObject.class);

      if (data == null) {
        severe(
          "auth_data is not a valid Boosty auth object. Copy the \"auth\" value from boosty.to again."
        );
        return;
      }

      this.accessToken = readString(data, "accessToken", "access_token");
      this.refreshToken = readString(data, "refreshToken", "refresh_token");
      this.expiresAt = normalizeExpiresAt(
        readLong(data, "expiresAt", "expires_at")
      );

      if (clientId == null || clientId.isEmpty()) {
        String storedClientId = readString(data, "_clientId", "device_id");
        if (storedClientId != null) {
          this.clientId = storedClientId;
        }
      }
    } catch (Exception e) {
      severe(
        "Failed to parse auth_data (" +
        e.getClass().getSimpleName() +
        "). Copy the whole \"auth\" value from boosty.to into config.yml, it must stay in quotes."
      );
    }
  }

  private String decodePayload(String raw) {
    String value = raw.trim();

    if (
      value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")
    ) {
      value = value.substring(1, value.length() - 1).trim();
    }

    for (int attempt = 0; attempt < MAX_DECODE_ATTEMPTS; attempt++) {
      if (looksLikeJson(value)) {
        return value;
      }
      if (value.indexOf('%') < 0) {
        return value;
      }
      try {
        value = URLDecoder.decode(
          value.replace("+", "%2B"),
          StandardCharsets.UTF_8
        );
      } catch (Exception ignored) {
        return value;
      }
    }

    return value;
  }

  private boolean looksLikeJson(String value) {
    return value.startsWith("{") && value.endsWith("}") && value.contains("\"");
  }

  private String readString(JsonObject data, String primary, String fallback) {
    for (String key : new String[] { primary, fallback }) {
      if (data.has(key) && !data.get(key).isJsonNull()) {
        String value = data.get(key).getAsString();
        if (value != null && !value.isEmpty()) {
          return value;
        }
      }
    }
    return null;
  }

  private long readLong(JsonObject data, String primary, String fallback) {
    for (String key : new String[] { primary, fallback }) {
      if (data.has(key) && !data.get(key).isJsonNull()) {
        try {
          return data.get(key).getAsLong();
        } catch (Exception ignored) {
          return 0L;
        }
      }
    }
    return 0L;
  }

  private long normalizeExpiresAt(long value) {
    if (value > 0 && value < MIN_MILLIS_TIMESTAMP) {
      return value * 1000L;
    }
    return value;
  }

  private void saveAuth() {
    JsonObject data = new JsonObject();
    data.addProperty("accessToken", this.accessToken);
    data.addProperty("refreshToken", this.refreshToken);
    data.addProperty("expiresAt", this.expiresAt);
    data.addProperty("isNewUser", false);

    String jsonString = client.getGson().toJson(data);
    String encoded = URLEncoder.encode(jsonString, StandardCharsets.UTF_8);

    FileConfiguration config = client.getPlugin().getConfig();
    config.set("auth.auth_data", encoded);
    config.set("auth.client_id", this.clientId);
    client.getPlugin().saveConfig();
  }

  public CompletableFuture<Boolean> checkAndRefreshToken() {
    if (accessToken == null || accessToken.isEmpty()) {
      severe("Token is missing! Please specify auth_data in config.yml!");
      return CompletableFuture.completedFuture(false);
    }

    if (expiresAt - System.currentTimeMillis() > REFRESH_THRESHOLD_MS) {
      return CompletableFuture.completedFuture(true);
    }

    if (refreshToken == null || refreshToken.isEmpty()) {
      warning(
        "auth_data has no refresh_token, the current access token is used as is."
      );
      return CompletableFuture.completedFuture(true);
    }

    if (clientId == null || clientId.isEmpty() || clientId.contains("вставьте")) {
      severe(
        "Invalid Client ID! Copy the \"_clientId\" value from boosty.to into auth.client_id in config.yml, Boosty rejects a refresh without it."
      );
      return CompletableFuture.completedFuture(false);
    }

    return requestRefresh(true)
      .thenCompose(result ->
        result == RefreshResult.RETRY_WITHOUT_BEARER
          ? requestRefresh(false)
          : CompletableFuture.completedFuture(result)
      )
      .thenApply(result -> {
        if (result == RefreshResult.SUCCESS) {
          return true;
        }
        if (result == RefreshResult.INVALID) {
          return false;
        }
        return expiresAt - System.currentTimeMillis() > 0;
      });
  }

  private CompletableFuture<RefreshResult> requestRefresh(boolean withBearer) {
    try {
      String payload =
        "device_id=" +
        encode(clientId) +
        "&device_os=web" +
        "&grant_type=refresh_token" +
        "&refresh_token=" +
        encode(refreshToken);

      HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(TOKEN_URL))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT)
        .header("Origin", "https://boosty.to")
        .header("Referer", "https://boosty.to/")
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

      if (withBearer) {
        builder.header("Authorization", "Bearer " + accessToken);
      }

      return client
        .getHttpClient()
        .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
        .thenApply(response -> handleRefreshResponse(response, withBearer))
        .exceptionally(ex -> {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          warning(
            "Network error while refreshing the Boosty token: " +
            cause.getClass().getSimpleName()
          );
          return RefreshResult.TEMPORARY;
        });
    } catch (Exception e) {
      severe(
        "Failed to build the token refresh request: " +
        e.getClass().getSimpleName()
      );
      return CompletableFuture.completedFuture(RefreshResult.TEMPORARY);
    }
  }

  private RefreshResult handleRefreshResponse(
    HttpResponse<String> response,
    boolean withBearer
  ) {
    int code = response.statusCode();

    if (code == 200) {
      return applyRefreshedTokens(response.body());
    }

    if (code == 400) {
      if (withBearer) {
        return RefreshResult.RETRY_WITHOUT_BEARER;
      }
      severe(
        "Boosty rejected the refresh token (400: " +
        describeError(response.body()) +
        "). The refresh token is expired or was already used: copy the \"auth\" value from boosty.to into auth_data and \"_clientId\" into client_id again."
      );
      return RefreshResult.INVALID;
    }

    if (code == 401 || code == 403) {
      severe(
        "Boosty token is invalid (" +
        code +
        ")! Please update auth_data in config.yml!"
      );
      return RefreshResult.INVALID;
    }

    warning("Failed to refresh token. Response code: " + code);
    return RefreshResult.TEMPORARY;
  }

  private RefreshResult applyRefreshedTokens(String body) {
    try {
      JsonObject data = client.getGson().fromJson(body, JsonObject.class);
      String newAccess = data == null
        ? null
        : readString(data, "access_token", "accessToken");

      if (newAccess == null) {
        warning(
          "Boosty answered 200 without access_token, the current token is kept."
        );
        return RefreshResult.TEMPORARY;
      }

      String newRefresh = readString(data, "refresh_token", "refreshToken");

      this.accessToken = newAccess;
      if (newRefresh != null) {
        this.refreshToken = newRefresh;
      }
      this.expiresAt = System.currentTimeMillis() + lifetimeFrom(data);
      saveAuth();

      client.getPlugin().getLogger().info("Boosty token refreshed.");
      return RefreshResult.SUCCESS;
    } catch (Exception e) {
      warning(
        "Failed to read the token refresh answer: " +
        e.getClass().getSimpleName()
      );
      return RefreshResult.TEMPORARY;
    }
  }

  private long lifetimeFrom(JsonObject data) {
    long seconds = readLong(data, "expires_in", "expiresIn");
    if (seconds > 0) {
      return seconds * 1000L;
    }
    return DEFAULT_TOKEN_LIFETIME_MS;
  }

  private String describeError(String body) {
    if (body == null || body.isEmpty()) {
      return "empty answer";
    }

    try {
      JsonObject data = client.getGson().fromJson(body, JsonObject.class);
      if (data != null) {
        String error = readString(data, "error", "code");
        String description = readString(
          data,
          "error_description",
          "error_message"
        );

        if (error != null && description != null) {
          return error + ": " + description;
        }
        if (error != null) {
          return error;
        }
        if (description != null) {
          return description;
        }
      }
    } catch (Exception ignored) {
      return trim(body);
    }

    return trim(body);
  }

  private String trim(String body) {
    return body.length() > 200 ? body.substring(0, 200) : body;
  }

  private String encode(String value) {
    return URLEncoder.encode(
      value == null ? "" : value,
      StandardCharsets.UTF_8
    );
  }

  private void warning(String message) {
    client.getPlugin().getLogger().warning(message);
  }

  private void severe(String message) {
    client.getPlugin().getLogger().severe(message);
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
