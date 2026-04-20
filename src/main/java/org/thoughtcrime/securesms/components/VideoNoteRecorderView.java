package org.thoughtcrime.securesms.components;

import android.Manifest;
import android.content.Context;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.ViewUtil;

public final class VideoNoteRecorderView extends FrameLayout implements View.OnTouchListener {

  private static final int LONG_PRESS_THRESHOLD_MS = 150;

  enum State {
    NOT_RUNNING,
    RUNNING_HELD,
    RUNNING_LOCKED
  }

  public static final int ANIMATION_DURATION = 200;

  private FloatingRecordButton floatingRecordButton;
  private LockDropTarget lockDropTarget;
  private @Nullable Listener listener;
  private @NonNull State state = State.NOT_RUNNING;

  private float pendingDownX;
  private float pendingDownY;
  private final Handler longPressHandler = new Handler(Looper.getMainLooper());
  private @Nullable Runnable longPressRunnable;

  public VideoNoteRecorderView(Context context) {
    super(context);
  }

  public VideoNoteRecorderView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();

    floatingRecordButton =
        new FloatingRecordButton(getContext(), (ImageView) findViewById(R.id.quick_camera_fab));
    lockDropTarget = new LockDropTarget(getContext(), findViewById(R.id.camera_lock_drop_target));

    View recordButton = findViewById(R.id.quick_camera_toggle);
    recordButton.setOnTouchListener(this);
  }

  public void cancelAction() {
    if (state != State.NOT_RUNNING) {
      state = State.NOT_RUNNING;
      longPressHandler.removeCallbacksAndMessages(null);
      hideUi();
      if (listener != null) listener.onRecordCanceled();
    }
  }

  public boolean isRecordingLocked() {
    return state == State.RUNNING_LOCKED;
  }

  private void lockAction() {
    if (state == State.RUNNING_HELD) {
      state = State.RUNNING_LOCKED;
      hideUi();
      if (listener != null) listener.onRecordLocked();
    }
  }

  public void unlockAction() {
    if (state == State.RUNNING_LOCKED) {
      state = State.NOT_RUNNING;
      hideUi();
      if (listener != null) listener.onRecordReleased();
    }
  }

  private void hideUi() {
    floatingRecordButton.hide();
    lockDropTarget.hide();
  }

  @Override
  public boolean onTouch(View v, final MotionEvent event) {
    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        if (!Permissions.hasAll(
            getContext(), Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
          if (listener != null) listener.onRecordPermissionRequired();
        } else {
          pendingDownX = event.getX();
          pendingDownY = event.getY();
          longPressRunnable =
              () -> {
                state = State.RUNNING_HELD;
                floatingRecordButton.display(pendingDownX, pendingDownY);
                lockDropTarget.display();
                if (listener != null) listener.onRecordPressed();
              };
          longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS);
        }
        break;

      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_UP:
        if (state == State.RUNNING_HELD) {
          state = State.NOT_RUNNING;
          hideUi();
          if (listener != null) listener.onRecordReleased();
        } else if (state == State.NOT_RUNNING && longPressRunnable != null) {
          // Lifted before long-press threshold → single tap
          longPressHandler.removeCallbacks(longPressRunnable);
          longPressRunnable = null;
          Toast.makeText(getContext(), R.string.chat_record_video_explain, Toast.LENGTH_SHORT)
              .show();
        }
        break;

      case MotionEvent.ACTION_MOVE:
        if (state == State.RUNNING_HELD) {
          floatingRecordButton.moveTo(event.getX(), event.getY());
          if (listener != null)
            listener.onRecordMoved(floatingRecordButton.lastOffsetX, event.getRawX());

          int lockThreshold =
              getResources().getDimensionPixelSize(R.dimen.recording_voice_lock_target);
          if (floatingRecordButton.lastOffsetY <= lockThreshold) {
            lockAction();
          }
        }
        break;
    }
    return false;
  }

  public void setListener(@Nullable Listener listener) {
    this.listener = listener;
  }

  public interface Listener {
    void onRecordPressed();

    void onRecordReleased();

    void onRecordCanceled();

    void onRecordLocked();

    void onRecordMoved(float offsetX, float absoluteX);

    void onRecordPermissionRequired();
  }

  private static class FloatingRecordButton {

    private final ImageView recordButtonFab;

    private float startPositionX;
    private float startPositionY;
    float lastOffsetX;
    float lastOffsetY;

    FloatingRecordButton(Context context, ImageView recordButtonFab) {
      this.recordButtonFab = recordButtonFab;
      this.recordButtonFab
          .getBackground()
          .setColorFilter(
              context.getResources().getColor(R.color.audio_icon), PorterDuff.Mode.SRC_IN);
    }

    void display(float x, float y) {
      this.startPositionX = x;
      this.startPositionY = y;

      recordButtonFab.setVisibility(View.VISIBLE);

      AnimationSet animation = new AnimationSet(true);
      animation.addAnimation(
          new TranslateAnimation(
              Animation.ABSOLUTE, 0,
              Animation.ABSOLUTE, 0,
              Animation.ABSOLUTE, 0,
              Animation.ABSOLUTE, 0));
      animation.addAnimation(
          new ScaleAnimation(
              .5f, 1f, .5f, 1f,
              Animation.RELATIVE_TO_SELF, .5f,
              Animation.RELATIVE_TO_SELF, .5f));
      animation.setDuration(ANIMATION_DURATION);
      animation.setInterpolator(new OvershootInterpolator());

      recordButtonFab.startAnimation(animation);
    }

    void moveTo(float x, float y) {
      lastOffsetX = getXOffset(x);
      lastOffsetY = getYOffset(y);

      if (Math.abs(lastOffsetX) > Math.abs(lastOffsetY)) {
        lastOffsetY = 0;
      } else {
        lastOffsetX = 0;
      }

      recordButtonFab.setTranslationX(lastOffsetX);
      recordButtonFab.setTranslationY(lastOffsetY);
    }

    void hide() {
      recordButtonFab.setTranslationX(0);
      recordButtonFab.setTranslationY(0);
      if (recordButtonFab.getVisibility() != VISIBLE) return;

      AnimationSet animation = new AnimationSet(false);
      Animation scaleAnimation =
          new ScaleAnimation(
              1, 0.5f, 1, 0.5f,
              Animation.RELATIVE_TO_SELF, 0.5f,
              Animation.RELATIVE_TO_SELF, 0.5f);
      Animation translateAnimation =
          new TranslateAnimation(
              Animation.ABSOLUTE, lastOffsetX,
              Animation.ABSOLUTE, 0,
              Animation.ABSOLUTE, lastOffsetY,
              Animation.ABSOLUTE, 0);

      scaleAnimation.setInterpolator(new AnticipateOvershootInterpolator(1.5f));
      translateAnimation.setInterpolator(new DecelerateInterpolator());
      animation.addAnimation(scaleAnimation);
      animation.addAnimation(translateAnimation);
      animation.setDuration(ANIMATION_DURATION);
      animation.setInterpolator(new AnticipateOvershootInterpolator(1.5f));

      recordButtonFab.setVisibility(View.GONE);
      recordButtonFab.clearAnimation();
      recordButtonFab.startAnimation(animation);
    }

    private float getXOffset(float x) {
      return ViewUtil.isLtr(recordButtonFab)
          ? -Math.max(0, this.startPositionX - x)
          : Math.max(0, x - this.startPositionX);
    }

    private float getYOffset(float y) {
      return Math.min(0, y - this.startPositionY);
    }
  }

  private static class LockDropTarget {

    private final View lockDropTarget;
    private final int dropTargetPosition;

    LockDropTarget(Context context, View lockDropTarget) {
      this.lockDropTarget = lockDropTarget;
      this.dropTargetPosition =
          context.getResources().getDimensionPixelSize(R.dimen.recording_voice_lock_target);
    }

    void display() {
      lockDropTarget.setScaleX(1);
      lockDropTarget.setScaleY(1);
      lockDropTarget.setAlpha(0);
      lockDropTarget.setTranslationY(0);
      lockDropTarget.setVisibility(VISIBLE);
      lockDropTarget
          .animate()
          .setStartDelay(ANIMATION_DURATION * 2)
          .setDuration(ANIMATION_DURATION)
          .setInterpolator(new DecelerateInterpolator())
          .translationY(dropTargetPosition)
          .alpha(1)
          .start();
    }

    void hide() {
      lockDropTarget
          .animate()
          .setStartDelay(0)
          .setDuration(ANIMATION_DURATION)
          .setInterpolator(new LinearInterpolator())
          .scaleX(0)
          .scaleY(0)
          .start();
    }
  }
}
