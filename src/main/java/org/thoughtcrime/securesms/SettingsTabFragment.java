package org.thoughtcrime.securesms;

import android.graphics.Color;
import android.os.Bundle;
import android.content.Intent;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import org.thoughtcrime.securesms.preferences.SettingsRootFragment;
import org.thoughtcrime.securesms.qr.BackupTransferActivity;
import org.thoughtcrime.securesms.util.ViewUtil;

/**
 * The "Settings" tab fragment hosted inside {@link ConversationListActivity}.
 *
 * <p>Hosts {@link SettingsRootFragment} - extracted from the inner class in
 * {@link ApplicationPreferencesActivity} - and manages the child back stack for
 * sub-preference screens (Notifications, Appearance, etc.).
 */
public class SettingsTabFragment extends Fragment {

  private Toolbar toolbar;
  private OnBackPressedCallback backCallback;
  private int advancedTapCount = 0;
  private long advancedLastTapTime = 0;
  private TextView settingsTitleView = null;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_settings_tab, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    toolbar = view.findViewById(R.id.toolbar);
    // Do NOT call setSupportActionBar here — this fragment starts hidden.
    // The toolbar is set in onHiddenChanged(false) when the tab becomes visible.

    // Apply bottom inset so sub-screens aren't hidden behind the system nav bar
    // when the BottomNavigationView is GONE (layout_above constraint disappears).
    View settingsContainer = view.findViewById(R.id.settings_container);
    ViewUtil.applyWindowInsets(settingsContainer, false, false, false, true);

    getChildFragmentManager().addOnBackStackChangedListener(this::syncActionBar);

    if (savedInstanceState == null) {
      getChildFragmentManager().beginTransaction()
          .replace(R.id.settings_container, new SettingsRootFragment())
          .commit();
    }

    // Starts disabled — the fragment starts hidden; onHiddenChanged drives enabled state.
    backCallback = new OnBackPressedCallback(false) {
      @Override
      public void handleOnBackPressed() {
        if (getChildFragmentManager().getBackStackEntryCount() > 0) {
          getChildFragmentManager().popBackStack();
        } else {
          setEnabled(false);
          requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
      }
    };
    requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);
  }

  /** Called by sub-preference screens (e.g. SettingsRootFragment) to show a child fragment. */
  public void showFragment(Fragment fragment) {
    getChildFragmentManager().beginTransaction()
        .replace(R.id.settings_container, fragment)
        .addToBackStack(null)
        .commit();
  }

  /** Called by SettingsRootFragment to show the BackupTransfer flow. */
  public void showBackupProvider() {
    Intent intent = new Intent(requireActivity(), BackupTransferActivity.class);
    intent.putExtra(
        BackupTransferActivity.TRANSFER_MODE,
        BackupTransferActivity.TransferMode.SENDER_SHOW_QR.getInt());
    startActivity(intent);
    requireActivity().overridePendingTransition(0, 0);
    requireActivity().finishAffinity();
  }

  public void reattachToolbar() {
    if (toolbar == null || !isAdded()) return;
    if (backCallback != null) backCallback.setEnabled(true);
    ((AppCompatActivity) requireActivity()).setSupportActionBar(toolbar);
    // setSupportActionBar() posts deferred initialization work internally via Handler.
    // If we call applyActionBarState() synchronously here, that deferred work runs
    // after us and resets DisplayHomeAsUpEnabled to false. Posting our call ensures
    // it runs after AppCompat's own post(), so the back arrow is not wiped out.
    toolbar.post(this::applyActionBarState);
  }

  @Override
  public void onHiddenChanged(boolean hidden) {
    super.onHiddenChanged(hidden);
    if (backCallback != null) backCallback.setEnabled(!hidden);
    if (hidden) {
      // Restore bottom nav when leaving the Settings tab
      setBottomNavVisible(true);
    } else {
      reattachToolbar();
    }
  }

  private void syncActionBar() {
    if (isHidden() || toolbar == null || !isAdded()) return;
    applyActionBarState();
  }

  private void applyActionBarState() {
    if (toolbar == null || !isAdded()) return;
    toolbar.setNavigationOnClickListener(v -> {
      if (getChildFragmentManager().getBackStackEntryCount() > 0) {
        getChildFragmentManager().popBackStack();
      }
    });
    ActionBar ab = ((AppCompatActivity) requireActivity()).getSupportActionBar();
    if (ab != null) {
      boolean isRoot = getChildFragmentManager().getBackStackEntryCount() == 0;
      ab.setDisplayHomeAsUpEnabled(!isRoot);
      if (isRoot) {
        setupEasterEggTitle(ab);
      } else {
        // Remove the clickable title view from the toolbar before switching to a sub-screen
        if (settingsTitleView != null && settingsTitleView.getParent() != null) {
          ((ViewGroup) settingsTitleView.getParent()).removeView(settingsTitleView);
        }
        ab.setDisplayOptions(
            ActionBar.DISPLAY_SHOW_TITLE,
            ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_SHOW_CUSTOM);
      }
    }
    // Hide bottom nav on any sub-screen, show it on root Settings
    setBottomNavVisible(getChildFragmentManager().getBackStackEntryCount() == 0);
  }

  private void setupEasterEggTitle(ActionBar ab) {
    // Create the clickable title view only once to avoid stacking on tab re-entry.
    // Each setSupportActionBar() call creates a fresh ActionBar wrapper that does not
    // know about the view the previous wrapper added to the Toolbar, so calling
    // setCustomView() again would duplicate the title. We reuse the same instance and
    // detach it from whatever parent it currently has before handing it to the new wrapper.
    if (settingsTitleView == null) {
      settingsTitleView = new TextView(requireActivity());
      settingsTitleView.setText(R.string.menu_settings);
      settingsTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
      settingsTitleView.setTextColor(Color.WHITE);
      settingsTitleView.setOnClickListener(v -> {
        long now = SystemClock.elapsedRealtime();
        if (now - advancedLastTapTime > 3000) {
          advancedTapCount = 0;
        }
        advancedLastTapTime = now;
        advancedTapCount++;
        if (advancedTapCount >= 20) {
          advancedTapCount = 0;
          Fragment f = getChildFragmentManager().findFragmentById(R.id.settings_container);
          if (f instanceof SettingsRootFragment) {
            ((SettingsRootFragment) f).revealAdvancedSettings();
          }
        }
      });
    }
    // Detach from previous parent (orphaned by the old ActionBar wrapper) before re-adding.
    if (settingsTitleView.getParent() != null) {
      ((ViewGroup) settingsTitleView.getParent()).removeView(settingsTitleView);
    }
    ab.setDisplayOptions(
        ActionBar.DISPLAY_SHOW_CUSTOM,
        ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_SHOW_CUSTOM);
    ab.setCustomView(settingsTitleView);
  }

  private void setBottomNavVisible(boolean visible) {
    if (!isAdded()) return;
    View bottomNav = requireActivity().findViewById(R.id.bottom_navigation);
    if (bottomNav != null) {
      bottomNav.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
  }
}
