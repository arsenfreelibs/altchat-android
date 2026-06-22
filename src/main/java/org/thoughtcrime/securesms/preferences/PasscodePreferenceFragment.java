package org.thoughtcrime.securesms.preferences;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.passcode.PasscodeManager;
import org.thoughtcrime.securesms.passcode.PasscodeSetupActivity;

/** "Passcode Lock" settings screen: enable/change/disable, auto-lock, fingerprint. */
public class PasscodePreferenceFragment extends CorrectedPreferenceFragment {

  private ActivityResultLauncher<Intent> setupLauncher;

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_passcode);

    setupLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> refreshUi());

    findPreference("passcode_change").setOnPreferenceClickListener(p -> {
      setupLauncher.launch(PasscodeSetupActivity.getChangeIntent(requireContext()));
      return true;
    });

    SwitchPreferenceCompat fingerprint = findPreference("passcode_fingerprint");
    fingerprint.setOnPreferenceChangeListener((p, value) -> {
      PasscodeManager.setFingerprintEnabled(requireContext(), (Boolean) value);
      return true;
    });

    ListPreference autoLock = findPreference("passcode_autolock");
    autoLock.setOnPreferenceChangeListener((p, value) -> {
      PasscodeManager.setAutoLockSeconds(requireContext(), Integer.parseInt((String) value));
      updateAutoLockSummary(autoLock, (String) value);
      return true;
    });

    Preference turnOff = findPreference("passcode_turn_off");
    SpannableString redTitle = new SpannableString(getString(R.string.alt_passcode_turn_off));
    redTitle.setSpan(
        new ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.red_500)),
        0, redTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    turnOff.setTitle(redTitle);
    turnOff.setOnPreferenceClickListener(p -> {
      new AlertDialog.Builder(requireActivity())
          .setTitle(R.string.alt_passcode_turn_off)
          .setMessage(R.string.alt_passcode_turn_off_confirm)
          .setPositiveButton(R.string.alt_passcode_turn_off, (d, w) -> {
            PasscodeManager.disable(requireContext());
            // passcode is off now -> return to the Privacy & Security screen
            getParentFragmentManager().popBackStack();
          })
          .setNegativeButton(R.string.cancel, null)
          .show();
      return true;
    });
  }

  @Override
  public void onResume() {
    super.onResume();
    ((AppCompatActivity) requireActivity())
        .getSupportActionBar()
        .setTitle(R.string.alt_passcode_title);
    refreshUi();
  }

  private void refreshUi() {
    // This screen is only reached while the passcode is enabled (creating one is launched directly
    // from the Privacy & Security screen). If it somehow became disabled, leave the screen.
    if (!PasscodeManager.isEnabled(requireContext())) {
      getParentFragmentManager().popBackStack();
      return;
    }

    SwitchPreferenceCompat fingerprint = findPreference("passcode_fingerprint");
    fingerprint.setVisible(isBiometricAvailable());
    fingerprint.setChecked(PasscodeManager.isFingerprintEnabled(requireContext()));

    ListPreference autoLock = findPreference("passcode_autolock");
    String value = String.valueOf(PasscodeManager.getAutoLockSeconds(requireContext()));
    autoLock.setValue(value);
    updateAutoLockSummary(autoLock, value);
  }

  private void updateAutoLockSummary(ListPreference autoLock, String value) {
    int index = autoLock.findIndexOfValue(value);
    if (index >= 0) {
      autoLock.setSummary(autoLock.getEntries()[index]);
    }
  }

  private boolean isBiometricAvailable() {
    return BiometricManager.from(requireContext())
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        == BiometricManager.BIOMETRIC_SUCCESS;
  }
}
