package onevnl.ru.elytrya.models;

import java.util.UUID;

public record BoostyUser(UUID uuid, String playerName, String boostyName, String levelName, String subscriptionStatus) {
  public BoostyUser(UUID uuid, String playerName, String boostyName, String levelName) {
    this(uuid, playerName, boostyName, levelName, "active");
  }

  public boolean isActive() { return "active".equalsIgnoreCase(subscriptionStatus); }
}
