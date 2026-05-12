package org.thoughtcrime.securesms.util.review;

import android.content.Context;

public final class AppReviewRequesterFactory {
  private AppReviewRequesterFactory() {}

  public static AppReviewRequester create(Context context) {
    return new GplayAppReviewRequester(context);
  }
}
