package org.thoughtcrime.securesms.proxy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * XOR-deobfuscation for the auto-proxy URL blob. Pure Java (no Android dependencies) so it can be
 * unit-tested on the JVM. The base64 transport is handled by the caller (android.util.Base64 at
 * runtime), this only reverses the XOR and splits the URL list.
 */
final class AutoProxyObfuscation {

  private AutoProxyObfuscation() {}

  /**
   * XOR {@code data} with the repeating {@code key}, decode as UTF-8, and return the trimmed,
   * non-empty newline-separated URLs. Returns an empty array for empty/null inputs.
   */
  static String[] deobfuscate(byte[] data, byte[] key) {
    if (data == null || key == null || data.length == 0 || key.length == 0) {
      return new String[0];
    }
    byte[] out = new byte[data.length];
    for (int i = 0; i < data.length; i++) {
      out[i] = (byte) (data[i] ^ key[i % key.length]);
    }
    String joined = new String(out, StandardCharsets.UTF_8);
    Arrays.fill(out, (byte) 0);
    ArrayList<String> urls = new ArrayList<>();
    for (String line : joined.split("\n")) {
      line = line.trim();
      if (!line.isEmpty()) {
        urls.add(line);
      }
    }
    return urls.toArray(new String[0]);
  }
}
