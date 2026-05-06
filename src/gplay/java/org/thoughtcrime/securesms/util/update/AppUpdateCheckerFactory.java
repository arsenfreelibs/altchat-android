package org.thoughtcrime.securesms.util.update;

import android.content.Context;
import org.thoughtcrime.securesms.BuildConfig;

/** gplay flavor: uses the Play In-App Updates API to detect available updates. */
public final class AppUpdateCheckerFactory {

  private AppUpdateCheckerFactory() {}

  public static AppUpdateChecker create(Context context) {
    if (BuildConfig.DEBUG) {
      // Play API always returns "no update" for debug APKs (version not in Store).
      // Force-show the update UI in debug builds for layout testing.
      return callback -> callback.onUpdateAvailable();
    }
    return new GplayAppUpdateChecker(context);
  }
}
