package org.thoughtcrime.securesms.util.review;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;

public class GplayAppReviewRequester implements AppReviewRequester {
  private static final String TAG = "GplayAppReviewRequester";
  private static final String PREF_OPENS = "pref_review_opens_count";
  private static final int TRIGGER_EVERY = 10;

  private final Context context;

  public GplayAppReviewRequester(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public void maybeRequestReview(Activity activity) {
    int prev = PreferenceManager.getDefaultSharedPreferences(context)
        .getInt(PREF_OPENS, 0);
    int opens = (prev >= TRIGGER_EVERY * 1000) ? 1 : prev + 1;
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit().putInt(PREF_OPENS, opens).apply();

    if (opens % TRIGGER_EVERY != 0) return;

    ReviewManager manager = ReviewManagerFactory.create(context);
    manager.requestReviewFlow().addOnCompleteListener(task -> {
      if (!task.isSuccessful()) {
        Log.w(TAG, "requestReviewFlow failed", task.getException());
        return;
      }
      if (activity.isFinishing() || activity.isDestroyed()) return;
      ReviewInfo reviewInfo = task.getResult();
      manager.launchReviewFlow(activity, reviewInfo)
          .addOnCompleteListener(t -> Log.i(TAG, "launchReviewFlow complete"));
    });
  }
}
