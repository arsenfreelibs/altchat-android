package org.thoughtcrime.securesms.passcode;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.thoughtcrime.securesms.BaseActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;

/**
 * Full-screen lock screen shown when the app is locked by the global passcode (see {@link
 * PasscodeManager}). Offers a numeric PIN pad and, when enabled and available, biometric unlock.
 *
 * <p>{@code FLAG_SECURE} is always set so the entered passcode never appears in screenshots or the
 * task switcher preview. The back button minimizes the app instead of bypassing the lock.
 */
public class PasscodeActivity extends BaseActionBarActivity {

  private static final int BIOMETRIC_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK;

  private final StringBuilder entered = new StringBuilder();
  private List<View> keypadButtons = new ArrayList<>();
  private LinearLayout dotsContainer;
  private TextView errorView;
  private CountDownTimer lockoutTimer;
  private boolean biometricPromptShown;

  public PasscodeActivity() {
    dynamicTheme = new DynamicNoActionBarTheme();
  }

  /** Intent that brings up (or to front) the lock screen as its own task. */
  public static Intent getLockIntent(Context context) {
    Intent intent = new Intent(context, PasscodeActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    return intent;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    setContentView(R.layout.activity_passcode);

    ((TextView) findViewById(R.id.passcode_title))
        .setText(getString(R.string.alt_passcode_locked_title, getString(R.string.app_name)));
    dotsContainer = findViewById(R.id.passcode_dots);
    errorView = findViewById(R.id.passcode_error);

    buildKeypad();
    refreshDots();
  }

  @Override
  protected void onResume() {
    super.onResume();
    applyLockoutState();
    maybeShowBiometricPrompt();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (lockoutTimer != null) {
      lockoutTimer.cancel();
    }
  }

  @Override
  public void onBackPressed() {
    // Never let the back button slip past the lock; just send the app to the background.
    moveTaskToBack(true);
  }

  // ---------------------------------------------------------------------------
  // keypad
  // ---------------------------------------------------------------------------

  private void buildKeypad() {
    GridLayout keypad = findViewById(R.id.passcode_keypad);

    ImageButton biometric = PasscodeKeypad.borderlessIconButton(
        this, R.drawable.ic_passcode_fingerprint,
        getString(R.string.alt_passcode_use_fingerprint), v -> showBiometricPrompt());
    biometric.setVisibility(isBiometricAvailable() ? View.VISIBLE : View.INVISIBLE);

    keypadButtons = PasscodeKeypad.build(this, keypad, new PasscodeKeypad.Listener() {
      @Override
      public void onDigit(int digit) {
        PasscodeActivity.this.onDigit(digit);
      }

      @Override
      public void onBackspace() {
        PasscodeActivity.this.onBackspace();
      }
    }, biometric);
  }

  // ---------------------------------------------------------------------------
  // input handling
  // ---------------------------------------------------------------------------

  private void onDigit(int digit) {
    if (entered.length() >= PasscodeManager.getPasscodeLength()) {
      return;
    }
    clearError();
    entered.append(digit);
    refreshDots();
    if (entered.length() == PasscodeManager.getPasscodeLength()) {
      verify();
    }
  }

  private void onBackspace() {
    if (entered.length() > 0) {
      entered.deleteCharAt(entered.length() - 1);
      refreshDots();
    }
  }

  private void verify() {
    // Hashing (PBKDF2) is too slow for the UI thread; run it off-thread and disable input meanwhile.
    final String code = entered.toString();
    setKeypadEnabled(false);
    new Thread(() -> {
      final boolean ok = PasscodeManager.checkPasscode(this, code);
      runOnUiThread(() -> onVerified(ok));
    }).start();
  }

  private void onVerified(boolean ok) {
    setKeypadEnabled(true);
    if (ok) {
      PasscodeManager.unlock(this);
      setResult(RESULT_OK);
      finish();
    } else {
      PasscodeManager.recordFailedAttempt(this);
      entered.setLength(0);
      refreshDots();
      shakeDots();
      if (PasscodeManager.getLockoutRemainingMs(this) > 0) {
        applyLockoutState();
      } else {
        showError(getString(R.string.alt_passcode_wrong));
      }
    }
  }

  private void refreshDots() {
    PasscodeKeypad.refreshDots(dotsContainer, entered.length());
  }

  private void shakeDots() {
    PasscodeKeypad.shake(dotsContainer);
  }

  private void showError(String message) {
    errorView.setText(message);
    errorView.setVisibility(View.VISIBLE);
  }

  private void clearError() {
    errorView.setVisibility(View.INVISIBLE);
  }

  // ---------------------------------------------------------------------------
  // lockout after too many failed attempts
  // ---------------------------------------------------------------------------

  private void applyLockoutState() {
    long remaining = PasscodeManager.getLockoutRemainingMs(this);
    if (remaining <= 0) {
      setKeypadEnabled(true);
      return;
    }
    setKeypadEnabled(false);
    if (lockoutTimer != null) {
      lockoutTimer.cancel();
    }
    lockoutTimer = new CountDownTimer(remaining, 1000) {
      @Override
      public void onTick(long millisUntilFinished) {
        showError(getString(R.string.alt_passcode_locked_out, formatDuration(millisUntilFinished)));
      }

      @Override
      public void onFinish() {
        clearError();
        setKeypadEnabled(true);
      }
    }.start();
  }

  private void setKeypadEnabled(boolean enabled) {
    for (View button : keypadButtons) {
      button.setEnabled(enabled);
      button.setAlpha(enabled ? 1f : 0.4f);
    }
  }

  private static String formatDuration(long millis) {
    long totalSeconds = (millis + 999) / 1000;
    long minutes = totalSeconds / 60;
    long seconds = totalSeconds % 60;
    return String.format(Locale.US, "%d:%02d", minutes, seconds);
  }

  // ---------------------------------------------------------------------------
  // biometric unlock
  // ---------------------------------------------------------------------------

  private boolean isBiometricAvailable() {
    if (!PasscodeManager.isFingerprintEnabled(this)) {
      return false;
    }
    return BiometricManager.from(this).canAuthenticate(BIOMETRIC_AUTHENTICATORS)
        == BiometricManager.BIOMETRIC_SUCCESS;
  }

  private void maybeShowBiometricPrompt() {
    if (!biometricPromptShown && isBiometricAvailable()) {
      biometricPromptShown = true;
      showBiometricPrompt();
    }
  }

  private void showBiometricPrompt() {
    if (!isBiometricAvailable()) {
      return;
    }
    BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
        new BiometricPrompt.AuthenticationCallback() {
          @Override
          public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
            PasscodeManager.unlock(PasscodeActivity.this);
            setResult(RESULT_OK);
            finish();
          }
        });

    BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
        .setTitle(getString(R.string.alt_passcode_unlock_biometric_title, getString(R.string.app_name)))
        .setSubtitle(getString(R.string.alt_passcode_unlock_biometric_subtitle))
        .setNegativeButtonText(getString(R.string.alt_passcode_use_pin))
        .setAllowedAuthenticators(BIOMETRIC_AUTHENTICATORS)
        .build();
    prompt.authenticate(info);
  }
}
