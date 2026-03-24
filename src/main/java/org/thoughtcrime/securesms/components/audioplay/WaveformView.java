package org.thoughtcrime.securesms.components.audioplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import org.thoughtcrime.securesms.R;

/**
 * Displays an audio waveform as 40 vertical rounded bars.
 *
 * <p>Bars to the left of the current playback position are drawn at full opacity; bars to the right
 * are drawn at 25% opacity. A placeholder (all bars at 25% height) is shown while samples are
 * still loading.
 *
 * <p>Supports seek-by-tap and seek-by-drag via {@link SeekListener}.
 */
public class WaveformView extends View {

  private static final int BAR_COUNT = 40;
  private static final float BAR_WIDTH_FRACTION = 0.6f; // 60% of slot
  private static final float PLACEHOLDER_HEIGHT_FRACTION = 0.25f;
  private static final int UNPLAYED_ALPHA = 64; // ~25% of 255

  public interface SeekListener {
    /** @param progress value in [0.0, 1.0] */
    void onSeek(float progress);
    void onSeekStart();
    void onSeekEnd(float progress);
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF barRect = new RectF();
  private final GestureDetector gestureDetector;

  private @Nullable float[] samples;
  private float progress = 0f; // [0, 1]
  private @Nullable SeekListener seekListener;
  private boolean isTouchEnabled = true;

  private float cornerRadiusPx;

  public WaveformView(@NonNull Context context) {
    this(context, null);
  }

  public WaveformView(@NonNull Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public WaveformView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    paint.setColor(ContextCompat.getColor(context, R.color.audio_icon));
    cornerRadiusPx = context.getResources().getDisplayMetrics().density * 2f; // 2dp

    gestureDetector =
        new GestureDetector(
            context,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onSingleTapUp(@NonNull MotionEvent e) {
                // Notify seek position; onSeekEnd is always fired by ACTION_UP below
                notifySeek(e.getX());
                return true;
              }
            });
  }

  /** Sets the PCM amplitude buckets. Pass {@code null} to show the loading placeholder. */
  public void setSamples(@Nullable float[] samples) {
    this.samples = samples;
    invalidate();
  }

  /**
   * Sets the current playback progress.
   *
   * @param progress value in [0.0, 1.0]
   */
  public void setProgress(float progress) {
    this.progress = Math.max(0f, Math.min(1f, progress));
    invalidate();
  }

  public void setSeekListener(@Nullable SeekListener listener) {
    this.seekListener = listener;
  }

  /** When {@code false}, touch events are ignored (e.g. multi-select mode). */
  public void setTouchEnabled(boolean enabled) {
    this.isTouchEnabled = enabled;
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas) {
    int width = getWidth();
    int height = getHeight();
    if (width == 0 || height == 0) return;

    float playedX = progress * width;
    float slotWidth = (float) width / BAR_COUNT;
    float barWidth = slotWidth * BAR_WIDTH_FRACTION;

    for (int i = 0; i < BAR_COUNT; i++) {
      float amplitude = getAmplitude(i);
      float barHeight = Math.max(cornerRadiusPx * 2, amplitude * height);

      float left = i * slotWidth + (slotWidth - barWidth) / 2f;
      float right = left + barWidth;
      float top = (height - barHeight) / 2f;
      float bottom = top + barHeight;

      barRect.set(left, top, right, bottom);

      boolean isPlayed = (left + barWidth / 2f) < playedX;
      paint.setAlpha(isPlayed ? 255 : UNPLAYED_ALPHA);

      canvas.drawRoundRect(barRect, cornerRadiusPx, cornerRadiusPx, paint);
    }
  }

  private float getAmplitude(int barIndex) {
    if (samples == null || samples.length == 0) {
      return PLACEHOLDER_HEIGHT_FRACTION;
    }
    // Map barIndex to samples array (samples might not be exactly BAR_COUNT length)
    int sampleIndex = barIndex * samples.length / BAR_COUNT;
    return samples[Math.min(sampleIndex, samples.length - 1)];
  }

  @Override
  public boolean onTouchEvent(@NonNull MotionEvent event) {
    if (!isTouchEnabled || seekListener == null) return false;

    gestureDetector.onTouchEvent(event);

    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        seekListener.onSeekStart();
        return true;

      case MotionEvent.ACTION_MOVE:
        notifySeek(event.getX());
        return true;

      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        seekListener.onSeekEnd(progressForX(event.getX()));
        return true;
    }
    return false;
  }

  private void notifySeek(float x) {
    float p = progressForX(x);
    setProgress(p);
    if (seekListener != null) seekListener.onSeek(p);
  }

  private float progressForX(float x) {
    int width = getWidth();
    if (width == 0) return 0f;
    return Math.max(0f, Math.min(1f, x / width));
  }
}
