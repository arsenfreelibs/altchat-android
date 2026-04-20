package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class VideoNoteProgressRing extends View {

  private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF oval = new RectF();
  private float progress = 0f;

  public VideoNoteProgressRing(Context context) {
    super(context);
    init(false);
  }

  public VideoNoteProgressRing(Context context, AttributeSet attrs) {
    super(context, attrs);
    init(false);
  }

  private void init(boolean whiteMode) {
    float density = getResources().getDisplayMetrics().density;
    float strokePx = density * (whiteMode ? 4f : 5f);

    trackPaint.setStyle(Paint.Style.STROKE);
    trackPaint.setStrokeWidth(strokePx);
    trackPaint.setColor(whiteMode ? 0x00000000 : 0x4DFFFFFF);
    trackPaint.setStrokeCap(Paint.Cap.ROUND);

    sweepPaint.setStyle(Paint.Style.STROKE);
    sweepPaint.setStrokeWidth(strokePx);
    sweepPaint.setColor(whiteMode ? 0xFFFFFFFF : 0xFFE53935);
    sweepPaint.setStrokeCap(Paint.Cap.ROUND);
  }

  /** Call once to switch to playback mode: white sweep, no track, 4dp stroke. */
  public void configureAsPlaybackRing() {
    float strokePx = getResources().getDisplayMetrics().density * 4f;
    trackPaint.setColor(0x33FFFFFF); // faint white circle — visible immediately on tap
    trackPaint.setStrokeWidth(strokePx);
    sweepPaint.setColor(0xFFFFFFFF);
    sweepPaint.setStrokeWidth(strokePx);
    invalidate();
  }

  public void setProgress(float progress) {
    this.progress = Math.min(1f, Math.max(0f, progress));
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float half = trackPaint.getStrokeWidth() / 2f;
    oval.set(half, half, getWidth() - half, getHeight() - half);
    canvas.drawOval(oval, trackPaint);
    if (progress > 0f) {
      canvas.drawArc(oval, -90f, 360f * progress, false, sweepPaint);
    }
  }
}
