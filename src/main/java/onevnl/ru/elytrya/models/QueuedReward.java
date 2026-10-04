package onevnl.ru.elytrya.models;

import java.util.UUID;

public record QueuedReward(
  UUID uuid,
  String action,
  String levelName,
  String boostyName,
  long createdAt,
  String id,
  String playerName
) {
  public boolean isOlderThan(long millis) {
    if (millis <= 0) return false;
    return System.currentTimeMillis() - createdAt > millis;
  }
}
