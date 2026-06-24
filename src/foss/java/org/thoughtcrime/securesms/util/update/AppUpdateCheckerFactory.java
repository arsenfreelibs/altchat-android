package org.thoughtcrime.securesms.util.update;

import android.content.Context;
import org.thoughtcrime.securesms.BuildConfig;

/** foss flavor: no-op — F-Droid handles update notifications externally. */
public final class AppUpdateCheckerFactory {

  private AppUpdateCheckerFactory() {}

  public static AppUpdateChecker create(Context context) {
    return callback -> {
      if (BuildConfig.DEBUG) {
        // Force-show the update UI in debug builds for layout testing.
        callback.onUpdateAvailable();
      }
      // In release: F-Droid notifies users about updates; nothing to do here.
    };
  }
}
