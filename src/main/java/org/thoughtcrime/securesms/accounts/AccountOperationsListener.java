package org.thoughtcrime.securesms.accounts;

/**
 * Implemented by any Activity that hosts account-switching UI (AccountSelectionListFragment).
 */
public interface AccountOperationsListener {
  void onProfileSwitched(int profileId);
  void onDeleteProfile(int profileId);
}
