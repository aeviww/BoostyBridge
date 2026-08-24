package onevnl.ru.elytrya.api.managers;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.models.SubscriberSnapshot;
import onevnl.ru.elytrya.util.BoostyNameValidator;
import onevnl.ru.elytrya.util.RewardLevels;

public class BlogManager {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_OFFSET = 50000;

    private final BoostyClient client;
    private volatile String blogHref;

    public BlogManager(BoostyClient client) {
        this.client = client;
    }

    public static String subscriberKey(String name) {
        return BoostyNameValidator.normalize(name).toLowerCase(Locale.ROOT);
    }

    public void invalidateBlogHref() {
        this.blogHref = null;
    }

    public CompletableFuture<String> getBlogHref() {
        String cached = this.blogHref;
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        AuthManager auth = client.getAuthManager();
        JsonObject cookieData = new JsonObject();
        cookieData.addProperty("accessToken", auth.getAccessToken());
        cookieData.addProperty("refreshToken", auth.getRefreshToken());
        cookieData.addProperty("expiresAt", String.valueOf(auth.getExpiresAt()));
        cookieData.addProperty("isEmptyUser", "0");
        cookieData.addProperty("redirectAppId", "web");
        cookieData.addProperty("_clientId", auth.getClientId());

        String encodedCookie = URLEncoder.encode(client.getGson().toJson(cookieData), StandardCharsets.UTF_8).replace("+", "%20");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://boosty.to"))
                .header("Cookie", "auth=" + encodedCookie)
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .header("User-Agent", AuthManager.USER_AGENT)
                .GET()
                .build();

        return client.getHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        client.getPlugin().getLogger().severe("Authorization token is invalid (401/403 error)!");
                        this.blogHref = null;
                        return null;
                    }
                    if (response.statusCode() != 200) {
                        client.debug("Unexpected status while resolving blog href: " + response.statusCode());
                        return null;
                    }
                    Pattern pattern = Pattern.compile("data-test-id=\"MAINFEED:ownBlogButton\"[^>]*href=\"([^\"]+)\"");
                    Matcher matcher = pattern.matcher(response.body());
                    if (matcher.find()) {
                        String href = matcher.group(1);
                        if (href.startsWith("/")) {
                            href = href.substring(1);
                        }
                        this.blogHref = href;
                        return this.blogHref;
                    }
                    client.debug("Could not find own blog link in the Boosty response.");
                    return null;
                });
    }

    public CompletableFuture<JsonObject> getBlogStats() {
        return getBlogHref().thenCompose(href -> {
            if (href == null) {
                return CompletableFuture.completedFuture(null);
            }

            AuthManager auth = client.getAuthManager();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.boosty.to/v1/blog/stat/" + href + "/current"))
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .header("X-Currency", "RUB")
                    .header("X-Locale", "ru_RU")
                    .header("X-App", "web")
                    .GET()
                    .build();

            return client.getHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 401 || response.statusCode() == 403) {
                            client.getPlugin().getLogger().severe("Invalid token while fetching stats (401/403 error)!");
                            invalidateBlogHref();
                        }
                        if (response.statusCode() == 200) {
                            return client.getGson().fromJson(response.body(), JsonObject.class);
                        }
                        return null;
                    });
        });
    }

    private CompletableFuture<JsonObject> searchSubscriber(String href, String targetName, int offset, int limit) {
        AuthManager auth = client.getAuthManager();
        String url = "https://api.boosty.to/v1/blog/" + href + "/subscribers?limit=" + limit + "&offset=" + offset;
        client.debug("Fetching from: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .header("X-App", "web")
                .GET()
                .build();

        String targetKey = subscriberKey(targetName);

        return client.getHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        client.getPlugin().getLogger().severe("Invalid token while checking subscriber (401/403 error)!");
                        invalidateBlogHref();
                    }
                    client.debug("Response code: " + response.statusCode());
                    if (response.statusCode() == 200) {
                        JsonObject json = client.getGson().fromJson(response.body(), JsonObject.class);
                        if (json != null && json.has("data")) {
                            JsonArray data = json.getAsJsonArray("data");

                            if (data.size() == 0) {
                                client.debug("No more subscribers found. Reached end of list at offset " + offset);
                                return CompletableFuture.completedFuture(null);
                            }

                            for (JsonElement el : data) {
                                JsonObject sub = el.getAsJsonObject();
                                if (sub.has("name") && !sub.get("name").isJsonNull()) {
                                    String subName = sub.get("name").getAsString();
                                    if (subscriberKey(subName).equals(targetKey)) {
                                        client.debug("Match found at offset " + offset + "!");
                                        return CompletableFuture.completedFuture(sub);
                                    }
                                }
                            }

                            if (offset + limit > MAX_OFFSET) {
                                client.debug("Subscriber search stopped: offset limit reached.");
                                return CompletableFuture.completedFuture(null);
                            }

                            client.debug("Not found in current chunk. Fetching next " + limit + " subscribers...");
                            return searchSubscriber(href, targetName, offset + limit, limit);
                        }
                        client.debug("No 'data' element in JSON response.");
                        return CompletableFuture.completedFuture(null);
                    } else {
                        client.debug("Failed API response: " + response.body());
                        return CompletableFuture.completedFuture(null);
                    }
                });
    }

    public CompletableFuture<JsonObject> getSubscriberData(String name) {
        client.debug("Starting subscriber check for: '" + name + "'");
        return getBlogHref().thenCompose(href -> {
            if (href == null) {
                client.debug("Blog href is null, cannot check subscribers.");
                return CompletableFuture.completedFuture(null);
            }
            return searchSubscriber(href, name, 0, PAGE_SIZE);
        });
    }

    private CompletableFuture<Void> fetchAllSubscribers(String href, int offset, int limit, Map<String, String> result, AtomicReference<String> failure) {
        AuthManager auth = client.getAuthManager();
        String url = "https://api.boosty.to/v1/blog/" + href + "/subscribers?limit=" + limit + "&offset=" + offset;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .header("X-App", "web")
                .GET()
                .build();

        return client.getHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int code = response.statusCode();

                    if (code == 401 || code == 403) {
                        client.getPlugin().getLogger().severe("Invalid token during synchronization (401/403 error)!");
                        invalidateBlogHref();
                        failure.set("HTTP " + code + " - Boosty token is invalid or expired");
                        return CompletableFuture.<Void>completedFuture(null);
                    }

                    if (code != 200) {
                        failure.set("HTTP " + code + " from the subscribers endpoint");
                        client.debug("Subscriber page failed with code " + code);
                        return CompletableFuture.<Void>completedFuture(null);
                    }

                    JsonObject json;
                    try {
                        json = client.getGson().fromJson(response.body(), JsonObject.class);
                    } catch (Exception e) {
                        failure.set("malformed JSON response (" + e.getClass().getSimpleName() + ")");
                        return CompletableFuture.<Void>completedFuture(null);
                    }

                    if (json == null || !json.has("data") || !json.get("data").isJsonArray()) {
                        failure.set("unexpected response without a 'data' array");
                        return CompletableFuture.<Void>completedFuture(null);
                    }

                    JsonArray data = json.getAsJsonArray("data");
                    if (data.size() == 0) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }

                    for (JsonElement el : data) {
                        if (!el.isJsonObject()) continue;
                        JsonObject sub = el.getAsJsonObject();
                        if (!sub.has("name") || sub.get("name").isJsonNull()) continue;

                        String levelName = "none";
                        if (sub.has("level") && !sub.get("level").isJsonNull()) {
                            JsonObject levelObj = sub.getAsJsonObject("level");
                            if (levelObj.has("name") && !levelObj.get("name").isJsonNull()) {
                                levelName = levelObj.get("name").getAsString();
                            }
                        }
                        result.put(subscriberKey(sub.get("name").getAsString()),
                                RewardLevels.canonical(client.getPlugin().getConfig(), levelName));
                    }

                    if (offset + limit > MAX_OFFSET) {
                        failure.set("subscriber list is longer than " + MAX_OFFSET + " entries");
                        return CompletableFuture.<Void>completedFuture(null);
                    }

                    return fetchAllSubscribers(href, offset + limit, limit, result, failure);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    failure.set("request error: " + cause.getClass().getSimpleName() +
                            (cause.getMessage() != null ? " (" + cause.getMessage() + ")" : ""));
                    return null;
                });
    }

    public CompletableFuture<SubscriberSnapshot> getSubscribersSnapshot() {
        return getBlogHref().thenCompose(href -> {
            if (href == null) {
                return CompletableFuture.completedFuture(
                        SubscriberSnapshot.failed("could not resolve the blog (invalid token or Boosty is unreachable)"));
            }

            Map<String, String> map = new ConcurrentHashMap<>();
            AtomicReference<String> failure = new AtomicReference<>();

            return fetchAllSubscribers(href, 0, PAGE_SIZE, map, failure)
                    .thenApply(v -> {
                        String reason = failure.get();
                        if (reason != null) {
                            return SubscriberSnapshot.failed(reason);
                        }
                        client.debug("Fetched " + map.size() + " subscribers from Boosty.");
                        return SubscriberSnapshot.ok(map);
                    });
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return SubscriberSnapshot.failed("request error: " + cause.getClass().getSimpleName());
        });
    }

    @Deprecated
    public CompletableFuture<Map<String, String>> getAllSubscribersMap() {
        return getSubscribersSnapshot().thenApply(SubscriberSnapshot::subscribers);
    }
}
