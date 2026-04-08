package org.thoughtcrime.securesms;

import android.os.Bundle;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
    ActionBar actionBar = ((AppCompatActivity) requireActivity()).getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(R.string.menu_settings);
    }
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
      ab.setDisplayHomeAsUpEnabled(
          getChildFragmentManager().getBackStackEntryCount() > 0);
    }
    // Hide bottom nav on any sub-screen, show it on root Settings
    setBottomNavVisible(getChildFragmentManager().getBackStackEntryCount() == 0);
  }

  private void setBottomNavVisible(boolean visible) {
    if (!isAdded()) return;
    View bottomNav = requireActivity().findViewById(R.id.bottom_navigation);
    if (bottomNav != null) {
      bottomNav.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
  }
}
