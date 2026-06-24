package org.thoughtcrime.securesms.preferences;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import org.thoughtcrime.securesms.ApplicationPreferencesActivity;
import org.thoughtcrime.securesms.ConnectivityActivity;
import org.thoughtcrime.securesms.CreateProfileActivity;
import org.thoughtcrime.securesms.LocalHelpActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.SettingsTabFragment;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.preferences.widgets.ProfilePreference;
import org.thoughtcrime.securesms.util.ScreenLockUtil;

/**
 * Root settings screen fragment.
 *
 * <p>Extracted from the {@code ApplicationPreferenceFragment} inner class in {@link
 * ApplicationPreferencesActivity} so it can also be hosted in {@link
 * org.thoughtcrime.securesms.SettingsTabFragment} inside {@link
 * org.thoughtcrime.securesms.ConversationListActivity}.
 */
public class SettingsRootFragment extends CorrectedPreferenceFragment
    implements DcEventCenter.DcEventDelegate {

  private static final String PREFERENCE_UPDATE_AVAILABLE = "preference_update_available";

  private static final String PREFERENCE_CATEGORY_PROFILE = "preference_category_profile";
  private static final String PREFERENCE_CATEGORY_NOTIFICATIONS =
      "preference_category_notifications";
  private static final String PREFERENCE_CATEGORY_APPEARANCE = "preference_category_appearance";
  private static final String PREFERENCE_CATEGORY_CHATS = "preference_category_chats";
  private static final String PREFERENCE_CATEGORY_PRIVACY = "preference_category_privacy";
  private static final String PREFERENCE_CATEGORY_MULTIDEVICE = "preference_category_multidevice";
  private static final String PREFERENCE_CATEGORY_ADVANCED = "preference_category_advanced";
  private static final String PREFERENCE_CATEGORY_CONNECTIVITY = "preference_category_connectivity";
  private static final String PREFERENCE_CATEGORY_HELP = "preference_category_help";

  private ActivityResultLauncher<Intent> screenLockLauncher;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);

    screenLockLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                getSettingsTabFragment().showBackupProvider();
              }
            });

    this.findPreference(PREFERENCE_UPDATE_AVAILABLE)
        .setOnPreferenceClickListener(
            preference -> {
              String pkg = requireActivity().getPackageName();
              Intent intent =
                  new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
              if (intent.resolveActivity(requireActivity().getPackageManager()) != null) {
                startActivity(intent);
              } else {
                startActivity(
                    new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
              }
              return true;
            });
    this.findPreference(PREFERENCE_UPDATE_AVAILABLE).setVisible(false);

    this.findPreference(PREFERENCE_CATEGORY_PROFILE)
        .setOnPreferenceClickListener(new ProfileClickListener());
    this.findPreference(PREFERENCE_CATEGORY_NOTIFICATIONS)
        .setOnPreferenceClickListener(new CategoryClickListener(PREFERENCE_CATEGORY_NOTIFICATIONS));
    this.findPreference(PREFERENCE_CATEGORY_CONNECTIVITY)
        .setOnPreferenceClickListener(new CategoryClickListener(PREFERENCE_CATEGORY_CONNECTIVITY));
    this.findPreference(PREFERENCE_CATEGORY_APPEARANCE)
        .setOnPreferenceClickListener(new CategoryClickListener(PREFERENCE_CATEGORY_APPEARANCE));
    this.findPreference(PREFERENCE_CATEGORY_CHATS)
        .setOnPreferenceClickListener(new CategoryClickListener(PREFERENCE_CATEGORY_CHATS));
    this.findPreference(PREFERENCE_CATEGORY_PRIVACY)
        .setOnPreferenceClickListener(new CategoryClickListener(PREFERENCE_CATEGORY_PRIVACY));
    this.findPreference(PREFERENCE_CATEGORY_MULTIDEVICE)
        .setOnPreferenceClickListener(new CategoryClickListener(PREFERENCE_CATEGORY_MULTIDEVICE));
    this.findPreference(PREFERENCE_CATEGORY_ADVANCED)
        .setOnPreferenceClickListener(new CategoryClickListener(PREFERENCE_CATEGORY_ADVANCED));
    this.findPreference(PREFERENCE_CATEGORY_ADVANCED).setVisible(false);

    this.findPreference(PREFERENCE_CATEGORY_HELP)
        .setOnPreferenceClickListener(new CategoryClickListener(PREFERENCE_CATEGORY_HELP));
    this.findPreference(PREFERENCE_CATEGORY_HELP).setEnabled(false);

    try {
      String versionName =
          requireActivity()
              .getPackageManager()
              .getPackageInfo(requireActivity().getPackageName(), 0)
              .versionName;
      this.findPreference("preference_app_version").setSummary("alt.chat v" + versionName);
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      this.findPreference("preference_app_version").setSummary("alt.chat");
    }

    DcHelper.getEventCenter(requireActivity())
        .addObserver(DcContext.DC_EVENT_CONNECTIVITY_CHANGED, this);
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences);
  }

  @Override
  public void onResume() {
    super.onResume();
    // Apply deferred update-available state unconditionally — this must run even
    // when the Settings tab is currently hidden, because onResume() is called right
    // after the fragment is committed (while the tab is still hidden), and the early
    // return below would otherwise skip this forever.
    Fragment parent = getParentFragment();
    if (parent instanceof SettingsTabFragment
        && ((SettingsTabFragment) parent).isUpdateAvailable()) {
      showUpdateAvailable();
    }
    if (getParentFragment() != null && getParentFragment().isHidden()) return;
    ActionBar actionBar = ((AppCompatActivity) requireActivity()).getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(R.string.menu_settings);
    }
    setCategorySummaries();
  }

  @Override
  public void onPause() {
    super.onPause();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    DcHelper.getEventCenter(requireActivity()).removeObservers(this);
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    if (event.getId() == DcContext.DC_EVENT_CONNECTIVITY_CHANGED) {
      this.findPreference(PREFERENCE_CATEGORY_CONNECTIVITY)
          .setSummary(
              DcHelper.getConnectivitySummary(
                  requireActivity(), getString(R.string.connectivity_connected)));
    }
  }

  private void setCategorySummaries() {
    ((ProfilePreference) this.findPreference(PREFERENCE_CATEGORY_PROFILE)).refresh();

    this.findPreference(PREFERENCE_CATEGORY_NOTIFICATIONS)
        .setSummary(NotificationsPreferenceFragment.getSummary(requireActivity()));
    this.findPreference(PREFERENCE_CATEGORY_APPEARANCE)
        .setSummary(AppearancePreferenceFragment.getSummary(requireActivity()));
    this.findPreference(PREFERENCE_CATEGORY_CHATS)
        .setSummary(ChatsPreferenceFragment.getSummary(requireActivity()));
    this.findPreference(PREFERENCE_CATEGORY_CONNECTIVITY)
        .setSummary(
            DcHelper.getConnectivitySummary(
                requireActivity(), getString(R.string.connectivity_connected)));
    this.findPreference(PREFERENCE_CATEGORY_HELP)
        .setSummary(AdvancedPreferenceFragment.getVersion(requireActivity()));
  }

  /** Returns the parent {@link SettingsTabFragment} when hosted inside the Settings tab. */
  private SettingsTabFragment getSettingsTabFragment() {
    return (SettingsTabFragment) requireParentFragment();
  }

  public void showUpdateAvailable() {
    Preference pref = findPreference(PREFERENCE_UPDATE_AVAILABLE);
    if (pref == null || pref.isVisible()) return;
    SpannableStringBuilder title = new SpannableStringBuilder(getString(R.string.update_available));
    title.append("  \u25CF"); // ● red dot
    title.setSpan(
        new ForegroundColorSpan(
            androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.update_dot_color)),
        title.length() - 1,
        title.length(),
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    pref.setTitle(title);
    pref.setVisible(true);
  }

  public void revealAdvancedSettings() {
    findPreference(PREFERENCE_CATEGORY_ADVANCED).setVisible(true);
    findPreference(PREFERENCE_CATEGORY_HELP).setEnabled(true);
    Toast.makeText(requireActivity(), R.string.menu_advanced, Toast.LENGTH_SHORT).show();
  }

  private class CategoryClickListener implements Preference.OnPreferenceClickListener {
    private final String category;

    CategoryClickListener(String category) {
      this.category = category;
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
      androidx.fragment.app.Fragment fragment = null;

      switch (category) {
        case PREFERENCE_CATEGORY_NOTIFICATIONS:
          NotificationManagerCompat notificationManager =
              NotificationManagerCompat.from(requireActivity());
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
              || notificationManager.areNotificationsEnabled()) {
            fragment = new NotificationsPreferenceFragment();
          } else {
            new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.notifications_disabled)
                .setMessage(R.string.perm_explain_access_to_notifications_denied)
                .setPositiveButton(
                    R.string.perm_continue,
                    (dialog, which) ->
                        requireActivity()
                            .startActivity(
                                Permissions.getApplicationSettingsIntent(requireActivity())))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
          }
          break;
        case PREFERENCE_CATEGORY_CONNECTIVITY:
          startActivity(new Intent(requireActivity(), ConnectivityActivity.class));
          break;
        case PREFERENCE_CATEGORY_APPEARANCE:
          fragment = new AppearancePreferenceFragment();
          break;
        case PREFERENCE_CATEGORY_CHATS:
          fragment = new ChatsPreferenceFragment();
          break;
        case PREFERENCE_CATEGORY_PRIVACY:
          fragment = new PrivacyPreferenceFragment();
          break;
        case PREFERENCE_CATEGORY_MULTIDEVICE:
          if (!ScreenLockUtil.applyScreenLock(
              requireActivity(),
              getString(R.string.multidevice_title),
              getString(R.string.multidevice_this_creates_a_qr_code)
                  + "\n\n"
                  + getString(R.string.enter_system_secret_to_continue),
              screenLockLauncher)) {
            new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.multidevice_title)
                .setMessage(R.string.multidevice_this_creates_a_qr_code)
                .setPositiveButton(
                    R.string.perm_continue,
                    (dialog, which) -> getSettingsTabFragment().showBackupProvider())
                .setNegativeButton(R.string.cancel, null)
                .show();
          }
          break;
        case PREFERENCE_CATEGORY_ADVANCED:
          fragment = new AdvancedPreferenceFragment();
          break;
        case PREFERENCE_CATEGORY_HELP:
          startActivity(new Intent(requireActivity(), LocalHelpActivity.class));
          break;
        default:
          throw new AssertionError();
      }

      if (fragment != null) {
        Bundle args = new Bundle();
        fragment.setArguments(args);
        getSettingsTabFragment().showFragment(fragment);
      }

      return true;
    }
  }

  private class ProfileClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      startActivity(new Intent(preference.getContext(), CreateProfileActivity.class));
      return true;
    }
  }
}
