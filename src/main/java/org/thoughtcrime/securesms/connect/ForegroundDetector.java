package org.thoughtcrime.securesms.connect;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.calls.CallActivity;
import org.thoughtcrime.securesms.passcode.PasscodeActivity;
import org.thoughtcrime.securesms.passcode.PasscodeManager;

@SuppressLint("NewApi")
public class ForegroundDetector implements Application.ActivityLifecycleCallbacks {

  private int refs = 0;
  private static ForegroundDetector Instance = null;
  private final ApplicationContext application;

  public static ForegroundDetector getInstance() {
    return Instance;
  }

  public ForegroundDetector(ApplicationContext application) {
    Instance = this;
    this.application = application;
    application.registerActivityLifecycleCallbacks(this);
  }

  public boolean isForeground() {
    return refs > 0;
  }

  public boolean isBackground() {
    return refs == 0;
  }

  @Override
  public void onActivityStarted(@NonNull Activity activity) {
    if (refs == 0) {
      Log.i(
          "DeltaChat",
          "++++++++++++++++++ first ForegroundDetector.onActivityStarted() ++++++++++++++++++");
      DcHelper.getAccounts(application).startIo();
      if (DcHelper.isNetworkConnected(application)) {
        new Thread(
                () -> {
                  Log.i("DeltaChat", "calling maybeNetwork()");
                  DcHelper.getAccounts(application).maybeNetwork();
                  Log.i("DeltaChat", "maybeNetwork() returned");
                })
            .start();
      }
    }

    refs++;

    maybeShowPasscodeLock(activity);
  }

  @Override
  public void onActivityStopped(@NonNull Activity activity) {
    if (refs <= 0) {
      Log.w("DeltaChat", "invalid call to ForegroundDetector.onActivityStopped()");
      return;
    }

    refs--;

    if (refs == 0) {
      Log.i(
          "DeltaChat",
          "++++++++++++++++++ last ForegroundDetector.onActivityStopped() ++++++++++++++++++");
      // Remember when the app went to the background, to measure idle time for passcode auto-lock.
      PasscodeManager.onAppBackgrounded();
    }
  }

  /**
   * Shows the passcode lock screen on top of {@code activity} when required (cold start, or the app
   * was in the background at least the configured auto-lock timeout). The lock screen itself is
   * skipped to avoid recursion.
   */
  private void maybeShowPasscodeLock(@NonNull Activity activity) {
    if (activity instanceof PasscodeActivity) {
      return;
    }
    // Let incoming/active calls pass through the lock straight to the call screen. The lock state
    // is intentionally not cleared, so navigating from the call back into the app re-raises the
    // lock screen and the app stays locked once the call ends.
    if (activity instanceof CallActivity) {
      return;
    }
    if (PasscodeManager.shouldLockOnForeground(activity)) {
      activity.startActivity(PasscodeActivity.getLockIntent(activity));
    }
  }

  @Override
  public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {}

  @Override
  public void onActivityResumed(@NonNull Activity activity) {}

  @Override
  public void onActivityPaused(@NonNull Activity activity) {
    // pause/resume will also be called when the app is partially covered by a dialog
  }

  @Override
  public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

  @Override
  public void onActivityDestroyed(@NonNull Activity activity) {}
}
