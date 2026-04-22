package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.view.PreviewView;
import org.thoughtcrime.securesms.R;

public class VideoNoteRecordingOverlay extends FrameLayout {

  private final PreviewView previewView;
  private final VideoNoteProgressRing progressRing;

  public VideoNoteRecordingOverlay(@NonNull Context context) {
    super(context);
    LayoutInflater.from(context).inflate(R.layout.video_note_recording_overlay, this, true);

    previewView = findViewById(R.id.video_note_preview);
    progressRing = findViewById(R.id.video_note_recording_ring);

    // Clip preview to circle
    previewView.setOutlineProvider(
        new android.view.ViewOutlineProvider() {
          @Override
          public void getOutline(android.view.View view, android.graphics.Outline outline) {
            outline.setOval(0, 0, view.getWidth(), view.getHeight());
          }
        });
    previewView.setClipToOutline(true);

    setClickable(false);
    setFocusable(false);
  }

  @Override
  public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
    return false;
  }

  @Override
  public boolean onTouchEvent(android.view.MotionEvent event) {
    return false;
  }

  public @NonNull PreviewView getPreviewView() {
    return previewView;
  }

  public void updateProgress(float progress) {
    progressRing.setProgress(progress);
  }

  @MainThread
  public void show(@NonNull ViewGroup parent) {
    if (getParent() != null) return;
    FrameLayout.LayoutParams lp =
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    setAlpha(0f);
    parent.addView(this, lp);
    animate().alpha(1f).setDuration(200).start();
  }

  @MainThread
  public void hide() {
    animate()
        .alpha(0f)
        .setDuration(200)
        .withEndAction(
            () -> {
              ViewGroup parent = (ViewGroup) getParent();
              if (parent != null) parent.removeView(this);
            })
        .start();
  }

  @Nullable
  public View getParentView() {
    return (View) getParent();
  }
}
