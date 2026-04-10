package org.thoughtcrime.securesms.calls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Draws three pulsing concentric mint-green rings that animate outward and fade.
 * Mirrors the iOS call screen "ringing wave" animation.
 *
 * Call {@link #startRinging()} when the call is in a ringing/connecting state,
 * and {@link #stopRinging()} once connected or ended.
 */
public class PulsingRingsView extends View {

  private static final int RING_COUNT = 3;
  private static final long RING_DURATION_MS = 1800;
  private static final long RING_DELAY_MS = 600; // offset between each ring

  // Mint-green with slight transparency
  private static final int RING_COLOR = 0xFF5AC87F;

  // Base radii (dp) for each ring — chosen to just exceed a 125dp avatar
  private static final float[] BASE_RADII_DP = {72f, 86f, 100f};

  // Extra expansion (dp) each ring travels from its base to its end
  private static final float EXPAND_DP = 28f;

  private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float[] fractions = new float[RING_COUNT];
  private final ValueAnimator[] animators = new ValueAnimator[RING_COUNT];

  private float density;

  public PulsingRingsView(Context context) {
    super(context);
    init(context);
  }

  public PulsingRingsView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init(context);
  }

  public PulsingRingsView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init(context);
  }

  private void init(Context context) {
    density = context.getResources().getDisplayMetrics().density;

    ringPaint.setStyle(Paint.Style.STROKE);
    ringPaint.setStrokeWidth(2f * density);
    ringPaint.setColor(RING_COLOR);

    for (int i = 0; i < RING_COUNT; i++) {
      final int idx = i;
      ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
      anim.setDuration(RING_DURATION_MS);
      anim.setStartDelay(i * RING_DELAY_MS);
      anim.setRepeatCount(ValueAnimator.INFINITE);
      anim.setRepeatMode(ValueAnimator.RESTART);
      anim.setInterpolator(new LinearInterpolator());
      anim.addUpdateListener(
          animation -> {
            fractions[idx] = (float) animation.getAnimatedValue();
            invalidate();
          });
      animators[i] = anim;
    }
  }

  /** Start the pulsing ring animation. Safe to call even if already running. */
  public void startRinging() {
    for (ValueAnimator anim : animators) {
      if (!anim.isRunning()) {
        anim.start();
      }
    }
  }

  /** Stop all ring animations and clear the canvas. */
  public void stopRinging() {
    for (int i = 0; i < RING_COUNT; i++) {
      animators[i].cancel();
      fractions[i] = 0f;
    }
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);

    float cx = getWidth() / 2f;
    float cy = getHeight() / 2f;

    for (int i = 0; i < RING_COUNT; i++) {
      float fraction = fractions[i];
      if (fraction <= 0f) continue;

      // Radius grows from base outward
      float baseRadius = BASE_RADII_DP[i] * density;
      float radius = baseRadius + fraction * EXPAND_DP * density;

      // Alpha fades 255 → 0 as fraction goes 0 → 1
      int alpha = (int) (255 * (1f - fraction));
      ringPaint.setAlpha(alpha);

      canvas.drawCircle(cx, cy, radius, ringPaint);
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    stopRinging();
  }
}
