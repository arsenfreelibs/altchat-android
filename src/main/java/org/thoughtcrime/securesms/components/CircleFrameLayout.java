package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * FrameLayout that clips its children to a circle via software clipPath().
 *
 * This is used for the video note fallback renderer where decoded frames are drawn
 * into a regular ImageView. Software clipping is reliable across devices, including
 * Samsung builds where live TextureView circular clipping proved inconsistent.
 */
public class CircleFrameLayout extends FrameLayout {

  private final Path clipPath = new Path();

  public CircleFrameLayout(Context context) {
    super(context);
    init();
  }

  public CircleFrameLayout(Context context, AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public CircleFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
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


