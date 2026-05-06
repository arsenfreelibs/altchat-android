package org.thoughtcrime.securesms.preferences;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/** Preference with a pulsing icon to draw attention to an available app update. */
public class UpdateAvailablePreference extends Preference {

  public UpdateAvailablePreference(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  public void onBindViewHolder(PreferenceViewHolder holder) {
    super.onBindViewHolder(holder);
    View icon = holder.itemView.findViewById(android.R.id.icon);
    if (icon == null) return;
    Object tag = icon.getTag();
    if (tag instanceof ObjectAnimator) {
      ((ObjectAnimator) tag).cancel();
    }
    ObjectAnimator pulse = ObjectAnimator.ofFloat(icon, "alpha", 1f, 0.1f, 1f);
    pulse.setDuration(1400);
    pulse.setRepeatCount(ObjectAnimator.INFINITE);
    pulse.setInterpolator(new AccelerateDecelerateInterpolator());
    pulse.start();
    icon.setTag(pulse);
  }
}
