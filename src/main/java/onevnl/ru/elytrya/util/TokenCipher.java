package onevnl.ru.elytrya.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class TokenCipher {

  public static final String PREFIX = "ENC:";

  private static final String KEY_FILE_NAME = "secret.key";
  private static final String ALGORITHM = "AES";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int KEY_SIZE_BITS = 256;
  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  public TokenCipher(File dataFolder) throws IOException, GeneralSecurityException {
    this.key = loadOrCreateKey(new File(dataFolder, KEY_FILE_NAME));
  }

  public static boolean isEncrypted(String value) {
    return value != null && value.startsWith(PREFIX);
  }

  public String encrypt(String plainText) throws GeneralSecurityException {
    if (plainText == null) return null;
    byte[] iv = new byte[IV_LENGTH];
    random.nextBytes(iv);

    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(
      Cipher.ENCRYPT_MODE,
      key,
      new GCMParameterSpec(TAG_LENGTH_BITS, iv)
    );
    byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

    byte[] combined = new byte[iv.length + encrypted.length];
    System.arraycopy(iv, 0, combined, 0, iv.length);
    System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

    return PREFIX + Base64.getEncoder().encodeToString(combined);
  }

  public String decrypt(String encoded) throws GeneralSecurityException {
    if (encoded == null) return null;
    if (!isEncrypted(encoded)) return encoded;

    byte[] combined = Base64.getDecoder().decode(encoded.substring(PREFIX.length()));
    if (combined.length <= IV_LENGTH) {
      throw new GeneralSecurityException("Encrypted payload is malformed");
    }

    byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
    byte[] payload = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);

    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(
      Cipher.DECRYPT_MODE,
      key,
      new GCMParameterSpec(TAG_LENGTH_BITS, iv)
    );
    return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
  }

  private SecretKey loadOrCreateKey(File keyFile)
    throws IOException, GeneralSecurityException {
    if (keyFile.exists()) {
      String encoded = new String(
        Files.readAllBytes(keyFile.toPath()),
        StandardCharsets.UTF_8
      ).trim();
      byte[] decoded = Base64.getDecoder().decode(encoded);
      return new SecretKeySpec(decoded, ALGORITHM);
    }

    File parent = keyFile.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }

    KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
    generator.init(KEY_SIZE_BITS);
    SecretKey generated = generator.generateKey();

    Files.write(
      keyFile.toPath(),
      Base64.getEncoder().encodeToString(generated.getEncoded()).getBytes(StandardCharsets.UTF_8)
    );
    restrictAccess(keyFile);

    return generated;
  }

  private void restrictAccess(File keyFile) {
    Path path = keyFile.toPath();
    try {
      Set<PosixFilePermission> permissions = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
      );
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException | IOException e) {
      keyFile.setReadable(false, false);
      keyFile.setReadable(true, true);
      keyFile.setWritable(false, false);
      keyFile.setWritable(true, true);
      keyFile.setExecutable(false, false);
    }
  }
}
