package onevnl.ru.elytrya.models;

import java.util.Collections;
import java.util.Map;

public record SubscriberSnapshot(
  boolean success,
  Map<String, String> subscribers,
  String failureReason
) {
  public static SubscriberSnapshot ok(Map<String, String> subscribers) {
    return new SubscriberSnapshot(
      true,
      subscribers == null ? Collections.emptyMap() : subscribers,
      null
    );
  }

  public static SubscriberSnapshot failed(String reason) {
    return new SubscriberSnapshot(
      false,
      Collections.emptyMap(),
      reason == null ? "unknown error" : reason
    );
  }

  public int size() {
    return subscribers.size();
  }
}
