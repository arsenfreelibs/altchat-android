package org.thoughtcrime.securesms.preferences;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.SettingsTabFragment;
import org.thoughtcrime.securesms.passcode.PasscodeManager;
import org.thoughtcrime.securesms.passcode.PasscodeSetupActivity;
import org.thoughtcrime.securesms.util.Prefs;

/** "Privacy and Security" settings screen; currently hosts the passcode lock entry. */
public class PrivacyPreferenceFragment extends CorrectedPreferenceFragment {

  private ActivityResultLauncher<Intent> createPasscodeLauncher;

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_privacy);

    createPasscodeLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
          // After a passcode was just created, go straight into its settings screen.
          if (PasscodeManager.isEnabled(requireContext())) {
            openPasscodeSettings();
          }
        });

    Preference passcode = findPreference("preference_passcode_lock");
    if (passcode != null) {
      passcode.setOnPreferenceClickListener(preference -> {
        if (PasscodeManager.isEnabled(requireContext())) {
          openPasscodeSettings();
        } else {
          // No passcode yet: skip the extra "Enable Passcode" step and create one directly.
          createPasscodeLauncher.launch(PasscodeSetupActivity.getCreateIntent(requireContext()));
        }
        return true;
      });
    }

    // Screen Security: mirrors the Advanced screen behaviour (restart hint on change).
    Preference screenSecurity = findPreference(Prefs.SCREEN_SECURITY_PREF);
    if (screenSecurity != null) {
      screenSecurity.setOnPreferenceChangeListener((p, value) -> {
        Prefs.setScreenSecurityEnabled(requireContext(), (Boolean) value);
        Toast.makeText(requireContext(),
            R.string.pref_screen_security_please_restart_hint, Toast.LENGTH_LONG).show();
        return true;
      });
    }
  }

  private void openPasscodeSettings() {
    ((SettingsTabFragment) requireParentFragment()).showFragment(new PasscodePreferenceFragment());
  }

  @Override
  public void onResume() {
    super.onResume();
    ((AppCompatActivity) requireActivity())
        .getSupportActionBar()
        .setTitle(R.string.alt_privacy_security_title);

    Preference passcode = findPreference("preference_passcode_lock");
    if (passcode != null) {
      passcode.setSummary(PasscodeManager.isEnabled(requireContext())
          ? R.string.alt_passcode_summary_on
          : R.string.alt_passcode_summary_off);
    }
  }
}
