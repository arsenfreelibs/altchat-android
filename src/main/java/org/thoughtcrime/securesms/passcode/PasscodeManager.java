package org.thoughtcrime.securesms.passcode;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.ThreadUtil;
import org.thoughtcrime.securesms.util.Util;

/**
 * Global (app-wide, account-independent) passcode lock.
 *
 * <p>The passcode is never stored in clear text: only a PBKDF2 hash + random salt are persisted in
 * the device-level default shared preferences (the same store {@link Prefs} uses), so the passcode
 * survives account switching and is shared across all profiles.
 *
 * <p>Keys are namespaced with {@code altchat_passcode_} to avoid collisions with upstream
 * DeltaChat preferences when merging.
 */
public final class PasscodeManager {

  private static final String TAG = "PasscodeManager";

  // --- persisted keys (device-level, namespaced for the altchat fork) ---
  private static final String ENABLED       = "altchat_passcode_enabled";
  private static final String HASH          = "altchat_passcode_hash";
  private static final String SALT          = "altchat_passcode_salt";
  private static final String AUTOLOCK      = "altchat_passcode_autolock_seconds";
  private static final String FINGERPRINT   = "altchat_passcode_fingerprint";
  private static final String FAILED        = "altchat_passcode_failed_attempts";
  private static final String LOCKOUT_UNTIL = "altchat_passcode_lockout_until"; // elapsedRealtime() ms

  // --- auto-lock, in seconds (-1 = disabled / only manual lock). The selectable option values live
  // in res/values/arrays.xml (passcode_autolock_values); only these two are referenced from code. ---
  public static final int AUTOLOCK_DISABLED = -1;
  public static final int AUTOLOCK_DEFAULT  = 60 * 60; // 1 hour

  private static final int PASSCODE_LENGTH = 4;

  // --- failed-attempt lockout ---
  private static final int  LOCKOUT_AFTER_ATTEMPTS = 5;   // start delaying from the 5th failure
  private static final long LOCKOUT_STEP_MS        = 30_000L; // grows per extra failure
  private static final long LOCKOUT_MAX_MS         = 30 * 60_000L;

  // --- PBKDF2 parameters ---
  private static final int    SALT_BYTES  = 16;
  private static final int    ITERATIONS  = 120_000;
  private static final int    KEY_BITS    = 256;
  private static final String ALGORITHM   = "PBKDF2WithHmacSHA256";

  // --- in-memory runtime state (per process) ---
  private static volatile boolean locked = false;
  private static volatile boolean initialized = false;     // false until the first foreground check after process start
  private static volatile long backgroundedAtElapsed = -1; // SystemClock.elapsedRealtime() when app went to background

  // Single background thread for the (deliberately slow) PBKDF2 hashing, so it never blocks the UI
  // thread and concurrent attempts are serialized rather than spawning a thread each.
  private static final ExecutorService HASH_EXECUTOR = ThreadUtil.newDynamicSingleThreadedExecutor();

  private PasscodeManager() {}

  // ---------------------------------------------------------------------------
  // enable / change / disable
  // ---------------------------------------------------------------------------

  public static boolean isEnabled(Context context) {
    return Prefs.getBooleanPreference(context, ENABLED, false);
  }

  /** Stores a new passcode (used both for initial setup and for "change passcode"). */
  public static void setPasscode(Context context, @NonNull String passcode) {
    byte[] salt = new byte[SALT_BYTES];
    new SecureRandom().nextBytes(salt);
    String hash = hash(passcode, salt);
    Prefs.setStringPreference(context, SALT, Base64.encodeToString(salt, Base64.NO_WRAP));
    Prefs.setStringPreference(context, HASH, hash);
    Prefs.setBooleanPreference(context, ENABLED, true);
    resetFailedAttempts(context);
  }

  /** Stores a new passcode off the UI thread; {@code onDone} runs on the main thread when finished. */
  public static void setPasscodeAsync(Context context, @NonNull String passcode, @NonNull Runnable onDone) {
    HASH_EXECUTOR.execute(() -> {
      setPasscode(context, passcode);
      Util.runOnMain(onDone);
    });
  }

  /** Checks the passcode off the UI thread; {@code onResult} receives the result on the main thread. */
  public static void checkPasscodeAsync(
      Context context, @NonNull String passcode, @NonNull Consumer<Boolean> onResult) {
    HASH_EXECUTOR.execute(() -> {
      boolean ok = checkPasscode(context, passcode);
      Util.runOnMain(() -> onResult.accept(ok));
    });
  }

  /** Constant-time check of an entered passcode against the stored hash. */
  public static boolean checkPasscode(Context context, @NonNull String passcode) {
    String storedHash = Prefs.getStringPreference(context, HASH, null);
    String storedSalt = Prefs.getStringPreference(context, SALT, null);
    if (storedHash == null || storedSalt == null) {
      return false;
    }
    byte[] salt = Base64.decode(storedSalt, Base64.NO_WRAP);
    String candidate = hash(passcode, salt);
    return constantTimeEquals(storedHash, candidate);
  }

  /** Turns the passcode off and clears all related secrets and runtime lock state. */
  public static void disable(Context context) {
    Prefs.setBooleanPreference(context, ENABLED, false);
    Prefs.removePreference(context, HASH);
    Prefs.removePreference(context, SALT);
    Prefs.removePreference(context, FINGERPRINT);
    Prefs.removePreference(context, AUTOLOCK);
    resetFailedAttempts(context);
    locked = false;
    backgroundedAtElapsed = -1;
  }

  public static int getPasscodeLength() {
    return PASSCODE_LENGTH;
  }

  // ---------------------------------------------------------------------------
  // settings: auto-lock timeout & fingerprint
  // ---------------------------------------------------------------------------

  /** @return auto-lock timeout in seconds, or {@link #AUTOLOCK_DISABLED}. */
  public static int getAutoLockSeconds(Context context) {
    return Prefs.getIntPreference(context, AUTOLOCK, AUTOLOCK_DEFAULT);
  }

  public static void setAutoLockSeconds(Context context, int seconds) {
    Prefs.setIntPreference(context, AUTOLOCK, seconds);
  }

  public static boolean isFingerprintEnabled(Context context) {
    return Prefs.getBooleanPreference(context, FINGERPRINT, false);
  }

  public static void setFingerprintEnabled(Context context, boolean enabled) {
    Prefs.setBooleanPreference(context, FINGERPRINT, enabled);
  }

  // ---------------------------------------------------------------------------
  // runtime lock state & auto-lock decision
  // ---------------------------------------------------------------------------

  public static boolean isLocked() {
    return locked;
  }

  /** Locks immediately (used by the toolbar lock button and on auto-lock). */
  public static void lock() {
    locked = true;
  }

  /** Marks the app as unlocked and resets failed attempts. Call after a successful unlock. */
  public static void unlock(Context context) {
    locked = false;
    backgroundedAtElapsed = -1;
    resetFailedAttempts(context);
  }

  /** Record the moment the app went to background, to measure idle time for auto-lock. */
  public static void onAppBackgrounded() {
    backgroundedAtElapsed = SystemClock.elapsedRealtime();
  }

  /**
   * Decides whether the lock screen must be shown when the app comes to the foreground.
   *
   * <ul>
   *   <li>If the passcode is disabled — never lock.
   *   <li>On the first foreground after process start (cold start) — lock.
   *   <li>If already locked — stay locked.
   *   <li>If auto-lock is disabled — do not auto-lock (only the manual button / cold start lock).
   *   <li>Otherwise lock if the app stayed in background at least the configured timeout.
   * </ul>
   *
   * <p>Uses {@link SystemClock#elapsedRealtime()} so changing the system clock cannot bypass it.
   */
  public static boolean shouldLockOnForeground(Context context) {
    // The first foreground check after process start counts as a cold start, regardless of whether
    // the passcode is currently enabled. Marking it here (before the isEnabled early-return) means
    // enabling the passcode mid-session does not immediately trigger a "cold start" lock.
    boolean coldStart = !initialized;
    initialized = true;

    if (!isEnabled(context)) {
      locked = false;
      return false;
    }
    if (coldStart) {
      // cold start with an enabled passcode -> always require unlock
      locked = true;
      return true;
    }
    if (locked) {
      return true;
    }
    int timeout = getAutoLockSeconds(context);
    if (timeout == AUTOLOCK_DISABLED) {
      return false;
    }
    if (backgroundedAtElapsed < 0) {
      return false;
    }
    long awayMs = SystemClock.elapsedRealtime() - backgroundedAtElapsed;
    if (awayMs >= timeout * 1000L) {
      locked = true;
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // failed-attempt lockout
  // ---------------------------------------------------------------------------

  /** Records a wrong passcode entry and arms a progressive lockout once the threshold is passed. */
  public static void recordFailedAttempt(Context context) {
    int attempts = Prefs.getIntPreference(context, FAILED, 0) + 1;
    Prefs.setIntPreference(context, FAILED, attempts);
    if (attempts >= LOCKOUT_AFTER_ATTEMPTS) {
      long delay = Math.min(LOCKOUT_MAX_MS, (attempts - LOCKOUT_AFTER_ATTEMPTS + 1) * LOCKOUT_STEP_MS);
      Prefs.setLongPreference(context, LOCKOUT_UNTIL, SystemClock.elapsedRealtime() + delay);
    }
  }

  private static void resetFailedAttempts(Context context) {
    Prefs.setIntPreference(context, FAILED, 0);
    Prefs.removePreference(context, LOCKOUT_UNTIL);
  }

  /**
   * @return remaining lockout time in ms during which passcode entry must be blocked, or 0 if entry
   *     is allowed. The attempt counter persists across process restarts; the timed window is based
   *     on {@link SystemClock#elapsedRealtime()} (immune to wall-clock changes).
   *
   *     <p>{@code elapsedRealtime()} resets on device reboot, so a deadline persisted in a previous
   *     boot session would otherwise be misread as a far-future value. A remaining time larger than
   *     the maximum possible lockout therefore means the stored deadline is stale (reboot happened)
   *     and the lockout is treated as expired.
   */
  public static long getLockoutRemainingMs(Context context) {
    long until = Prefs.getLongPreference(context, LOCKOUT_UNTIL, 0);
    if (until <= 0) {
      return 0;
    }
    long remaining = until - SystemClock.elapsedRealtime();
    if (remaining <= 0 || remaining > LOCKOUT_MAX_MS) {
      return 0;
    }
    return remaining;
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private static String hash(String passcode, byte[] salt) {
    try {
      KeySpec spec = new PBEKeySpec(passcode.toCharArray(), salt, ITERATIONS, KEY_BITS);
      SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
      byte[] derived = factory.generateSecret(spec).getEncoded();
      return Base64.encodeToString(derived, Base64.NO_WRAP);
    } catch (Exception e) {
      // Should never happen on supported Android versions; fail closed.
      Log.e(TAG, "passcode hashing failed", e);
      throw new RuntimeException(e);
    }
  }

  private static boolean constantTimeEquals(@Nullable String a, @Nullable String b) {
    if (a == null || b == null) {
      return false;
    }
    return MessageDigest.isEqual(
        a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
