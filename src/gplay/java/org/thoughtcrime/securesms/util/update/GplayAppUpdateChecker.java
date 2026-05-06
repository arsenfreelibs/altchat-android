package org.thoughtcrime.securesms.util.update;

import android.content.Context;
import android.util.Log;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.UpdateAvailability;

/** Checks for available updates via the Google Play In-App Updates API. */
public class GplayAppUpdateChecker implements AppUpdateChecker {

  private static final String TAG = "GplayAppUpdateChecker";

  private final Context context;

  public GplayAppUpdateChecker(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public void checkForUpdate(Callback callback) {
    AppUpdateManagerFactory.create(context)
        .getAppUpdateInfo()
        .addOnSuccessListener(
            info -> {
              if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                Log.i(TAG, "Update available");
                callback.onUpdateAvailable();
              }
            })
        .addOnFailureListener(e -> Log.w(TAG, "Update check failed", e));
  }
}
