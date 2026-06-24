package org.thoughtcrime.securesms.passcode;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.thoughtcrime.securesms.BaseActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;

/**
 * Guides the user through creating a new passcode (enter + confirm) or changing an existing one
 * (verify current, then enter + confirm). Reuses {@link R.layout#activity_passcode} without the
 * biometric button. Returns {@link #RESULT_OK} once a new passcode has been stored.
 */
public class PasscodeSetupActivity extends BaseActionBarActivity {

  /** Pass {@code true} to require the current passcode before setting a new one. */
  public static final String EXTRA_CHANGE = "change";

  private enum Step {
    ENTER_OLD,
    ENTER_NEW,
    CONFIRM_NEW
  }

  private final StringBuilder entered = new StringBuilder();
  private LinearLayout dotsContainer;
  private TextView titleView;
  private TextView subtitleView;
  private TextView errorView;

  private Step step;
  private String firstEntry;
  private boolean processing; // true while a passcode is being hashed off the UI thread

  public PasscodeSetupActivity() {
    dynamicTheme = new DynamicNoActionBarTheme();
  }

  public static Intent getCreateIntent(Context context) {
    return new Intent(context, PasscodeSetupActivity.class);
  }

  public static Intent getChangeIntent(Context context) {
    return new Intent(context, PasscodeSetupActivity.class).putExtra(EXTRA_CHANGE, true);
  }

  @Override
  protected boolean isAlwaysScreenSecure() {
    return true; // entering/creating a passcode must never appear in screenshots / recents
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    setContentView(R.layout.activity_passcode);

    findViewById(R.id.passcode_lock_icon).setVisibility(View.GONE);
    titleView = findViewById(R.id.passcode_title);
    subtitleView = findViewById(R.id.passcode_subtitle);
    dotsContainer = findViewById(R.id.passcode_dots);
    errorView = findViewById(R.id.passcode_error);

    buildKeypad();

    boolean change = getIntent().getBooleanExtra(EXTRA_CHANGE, false);
    goToStep(change ? Step.ENTER_OLD : Step.ENTER_NEW);
  }

  // ---------------------------------------------------------------------------
  // step handling
  // ---------------------------------------------------------------------------

  private void goToStep(Step next) {
    step = next;
    entered.setLength(0);
    refreshDots();
    clearError();
    switch (step) {
      case ENTER_OLD:
        titleView.setText(R.string.alt_passcode_enter_old_title);
        subtitleView.setText("");
        break;
      case ENTER_NEW:
        titleView.setText(R.string.alt_passcode_create_title);
        subtitleView.setText(R.string.alt_passcode_create_subtitle);
        break;
      case CONFIRM_NEW:
        titleView.setText(R.string.alt_passcode_confirm_title);
        subtitleView.setText(R.string.alt_passcode_confirm_subtitle);
        break;
    }
  }

  private void onComplete() {
    final String code = entered.toString();
    switch (step) {
      case ENTER_OLD:
        // Verifying the current passcode hashes off the UI thread (PBKDF2 is slow).
        processing = true;
        PasscodeManager.checkPasscodeAsync(
            this,
            code,
            ok -> {
              processing = false;
              if (ok) {
                goToStep(Step.ENTER_NEW);
              } else {
                rejectEntry(getString(R.string.alt_passcode_wrong));
              }
            });
        break;
      case ENTER_NEW:
        firstEntry = code;
        goToStep(Step.CONFIRM_NEW);
        break;
      case CONFIRM_NEW:
        if (code.equals(firstEntry)) {
          // Storing the new passcode hashes off the UI thread.
          processing = true;
          PasscodeManager.setPasscodeAsync(
              this,
              code,
              () -> {
                processing = false;
                setResult(RESULT_OK);
                finish();
              });
        } else {
          firstEntry = null;
          goToStep(Step.ENTER_NEW);
          rejectEntry(getString(R.string.alt_passcode_mismatch));
        }
        break;
    }
  }

  private void rejectEntry(String message) {
    entered.setLength(0);
    refreshDots();
    shakeDots();
    showError(message);
  }

  // ---------------------------------------------------------------------------
  // input
  // ---------------------------------------------------------------------------

  private void onDigit(int digit) {
    if (processing || entered.length() >= PasscodeManager.getPasscodeLength()) {
      return;
    }
    clearError();
    entered.append(digit);
    refreshDots();
    if (entered.length() == PasscodeManager.getPasscodeLength()) {
      onComplete();
    }
  }

  private void onBackspace() {
    if (processing) {
      return;
    }
    if (entered.length() > 0) {
      entered.deleteCharAt(entered.length() - 1);
      refreshDots();
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
  // keypad (no biometric cell)
  // ---------------------------------------------------------------------------

  private void buildKeypad() {
    GridLayout keypad = findViewById(R.id.passcode_keypad);
    PasscodeKeypad.build(
        this,
        keypad,
        new PasscodeKeypad.Listener() {
          @Override
          public void onDigit(int digit) {
            PasscodeSetupActivity.this.onDigit(digit);
          }

          @Override
          public void onBackspace() {
            PasscodeSetupActivity.this.onBackspace();
          }
        },
        null);
  }
}
