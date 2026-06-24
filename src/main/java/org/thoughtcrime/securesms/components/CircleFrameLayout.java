package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * FrameLayout that clips its children to a circle via canvas.clipPath().
 *
 * <p>No LAYER_TYPE_SOFTWARE — the RecyclerView parent no longer forces software rendering, so
 * hardware-accelerated canvas.clipPath() (supported since API 18) is used instead. TextureView
 * lives as a sibling (not inside this ViewGroup) and is clipped separately via
 * setClipToOutline(true) in VideoNoteView.init().
 */
public class CircleFrameLayout extends FrameLayout {

  private final Path clipPath = new Path();

  public CircleFrameLayout(Context context) {
    super(context);
  }

  public CircleFrameLayout(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public CircleFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    int w = getWidth();
    int h = getHeight();
    if (w == 0 || h == 0) {
      super.dispatchDraw(canvas);
      return;
    }
    clipPath.reset();
    clipPath.addCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, Path.Direction.CW);
    canvas.save();
    canvas.clipPath(clipPath);
    super.dispatchDraw(canvas);
    canvas.restore();
  }
}
