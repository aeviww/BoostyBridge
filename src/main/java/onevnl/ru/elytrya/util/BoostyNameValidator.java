package onevnl.ru.elytrya.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class BoostyNameValidator {

  public static final int MIN_LENGTH = 1;
  public static final int MAX_LENGTH = 128;

  private static final Pattern DISALLOWED = Pattern.compile(
    "[\\p{Cntrl}\\p{Cc}\\p{Cf}\u00a7&%;|`$<>\\\\\"\\n\\r\\t]"
  );
  private static final Pattern SPACES = Pattern.compile(
    "[\\s\\p{Zs}\\u00a0\\u1680\\u2000-\\u200a\\u202f\\u205f\\u3000]+"
  );
  private static final Pattern INVISIBLE = Pattern.compile(
    "[\\u00ad\\u180e\\u200b\\u200c\\u200d\\u2060\\ufeff]"
  );

  private BoostyNameValidator() {}

  public static String normalize(String boostyName) {
    if (boostyName == null) return "";
    String value = INVISIBLE.matcher(boostyName).replaceAll("");
    return SPACES.matcher(value).replaceAll(" ").trim();
  }

  public static String key(String boostyName) {
    return normalize(boostyName).toLowerCase(Locale.ROOT);
  }

  public static boolean isValid(String boostyName) {
    if (boostyName == null) return false;
    String value = normalize(boostyName);
    if (value.isEmpty()) return false;
    if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
      return false;
    }
    return !DISALLOWED.matcher(value).find();
  }

  public static String sanitize(String boostyName) {
    if (boostyName == null) return "";
    String value = DISALLOWED.matcher(normalize(boostyName)).replaceAll("");
    value = normalize(value);
    if (value.length() > MAX_LENGTH) {
      value = value.substring(0, MAX_LENGTH).trim();
    }
    return value;
  }
}
