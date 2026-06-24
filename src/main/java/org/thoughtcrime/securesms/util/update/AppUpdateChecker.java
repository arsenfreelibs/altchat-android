package org.thoughtcrime.securesms.util.update;

/** Checks whether a newer version of the app is available in the store. */
public interface AppUpdateChecker {

  /**
   * Start an asynchronous check. {@link Callback#onUpdateAvailable()} is called on the main thread
   * if an update exists.
   */
  void checkForUpdate(Callback callback);

  interface Callback {
    void onUpdateAvailable();
  }
}
