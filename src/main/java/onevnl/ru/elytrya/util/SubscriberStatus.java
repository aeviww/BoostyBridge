package onevnl.ru.elytrya.util;

import java.util.Locale;

public enum SubscriberStatus {
    ACTIVE, INACTIVE, UNKNOWN;

    public static SubscriberStatus parse(String status) {
        if (status == null) return UNKNOWN;
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "active" -> ACTIVE;
            case "inactive" -> INACTIVE;
            default -> UNKNOWN;
        };
    }
}
