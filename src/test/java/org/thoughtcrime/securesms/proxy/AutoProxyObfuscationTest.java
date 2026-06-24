package org.thoughtcrime.securesms.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

/**
 * Verifies {@link AutoProxyObfuscation#deobfuscate} reverses the XOR+base64 obfuscation that
 * build.gradle applies to proxy.properties, i.e. that the shipped blob decodes back to the exact
 * proxy URLs at runtime.
 */
public class AutoProxyObfuscationTest {

  // Must match autoProxyKeyBytes() in build.gradle.
  private static final byte[] KEY = {
    (byte) 0x5b, (byte) 0x21, (byte) 0x7e, (byte) 0x3c,
    (byte) 0xa9, (byte) 0x14, (byte) 0x6d, (byte) 0xf0,
    (byte) 0x82, (byte) 0x39, (byte) 0xcc, (byte) 0x4f,
    (byte) 0x1a, (byte) 0xe7, (byte) 0x90, (byte) 0x66
  };

  /** Mirrors the build.gradle obfuscation so the test exercises the real on-wire contract. */
  private static byte[] obfuscate(String urlsJoined) {
    byte[] data = urlsJoined.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[data.length];
    for (int i = 0; i < data.length; i++) {
      out[i] = (byte) (data[i] ^ KEY[i % KEY.length]);
    }
    return out;
  }

  @Test
  public void roundTripsMultipleProxies() {
    String[] urls = {"http://user1:pass1@10.0.0.1:8000", "http://user2:pass2@10.0.0.2:8000"};
    byte[] blob = obfuscate(String.join("\n", urls));
    assertThat(AutoProxyObfuscation.deobfuscate(blob, KEY)).containsExactly(urls);
  }

  @Test
  public void singleProxyRoundTrips() {
    String url = "http://only:one@9.9.9.9:8000";
    assertThat(AutoProxyObfuscation.deobfuscate(obfuscate(url), KEY)).containsExactly(url);
  }

  @Test
  public void trimsWhitespaceAndDropsBlankLines() {
    byte[] blob = obfuscate("  http://u:p@1.2.3.4:8000  \n\n   \nhttp://u:p@5.6.7.8:8000\n");
    assertThat(AutoProxyObfuscation.deobfuscate(blob, KEY))
        .containsExactly("http://u:p@1.2.3.4:8000", "http://u:p@5.6.7.8:8000");
  }

  @Test
  public void survivesBase64Transport() {
    // Exactly what build.gradle emits (base64) and what android.util.Base64 decodes at runtime.
    String[] urls = {"http://u:p@1.2.3.4:8000", "http://u:p@5.6.7.8:8000"};
    String blobB64 = Base64.getEncoder().encodeToString(obfuscate(String.join("\n", urls)));
    byte[] data = Base64.getDecoder().decode(blobB64);
    assertThat(AutoProxyObfuscation.deobfuscate(data, KEY)).containsExactly(urls);
  }

  @Test
  public void emptyOrNullInputsYieldEmptyList() {
    assertThat(AutoProxyObfuscation.deobfuscate(new byte[0], KEY)).isEmpty();
    assertThat(AutoProxyObfuscation.deobfuscate(obfuscate("x"), new byte[0])).isEmpty();
    assertThat(AutoProxyObfuscation.deobfuscate(null, KEY)).isEmpty();
    assertThat(AutoProxyObfuscation.deobfuscate(obfuscate("x"), null)).isEmpty();
  }

  @Test
  public void wrongKeyDoesNotYieldOriginalUrls() {
    byte[] blob = obfuscate("http://u:p@1.2.3.4:8000");
    byte[] wrongKey = KEY.clone();
    wrongKey[0] ^= 0xff;
    assertThat(AutoProxyObfuscation.deobfuscate(blob, wrongKey))
        .doesNotContain("http://u:p@1.2.3.4:8000");
  }
}
