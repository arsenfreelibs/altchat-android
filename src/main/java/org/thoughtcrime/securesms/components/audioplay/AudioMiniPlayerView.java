package org.thoughtcrime.securesms.components.audioplay;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.LifecycleOwner;
import org.thoughtcrime.securesms.R;

public class AudioMiniPlayerView extends ConstraintLayout {

  private ImageButton playPauseButton;
  private TextView speedButton;
  private ImageButton closeButton;
  private TextView titleView;
  private ProgressBar progressBar;

  private AudioPlaybackViewModel viewModel;
  private int currentMsgId = -1;

  public AudioMiniPlayerView(@NonNull Context context) {
    super(context);
    init(context);
  }

  public AudioMiniPlayerView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init(context);
  }

  public AudioMiniPlayerView(
      @NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init(context);
  }

  private void init(@NonNull Context context) {
    inflate(context, R.layout.audio_mini_player, this);
    playPauseButton = findViewById(R.id.mini_player_play_pause);
    speedButton = findViewById(R.id.mini_player_speed);
    closeButton = findViewById(R.id.mini_player_close);
    titleView = findViewById(R.id.mini_player_title);
    progressBar = findViewById(R.id.mini_player_progress);
  }

  public void setViewModel(@NonNull AudioPlaybackViewModel vm, @NonNull LifecycleOwner owner) {
    // Remove any previous observer registered on this LiveData from the same owner to prevent
    // duplicate updates if setViewModel() is called more than once (e.g. after config changes).
    if (this.viewModel != null) {
      this.viewModel.getPlaybackState().removeObservers(owner);
    }
    this.viewModel = vm;

    vm.getPlaybackState()
        .observe(
            owner,
            state -> {
              if (state == null
                  || state.getStatus() == AudioPlaybackState.PlaybackStatus.IDLE
                  || state.getStatus() == AudioPlaybackState.PlaybackStatus.ERROR) {
                setVisibility(GONE);
                return;
              }

              setVisibility(VISIBLE);
              currentMsgId = state.getMsgId();

              // Play/Pause icon
              boolean playing = state.getStatus() == AudioPlaybackState.PlaybackStatus.PLAYING;
              playPauseButton.setImageResource(
                  playing ? R.drawable.pause_icon : R.drawable.play_icon);

              // Title
              String sender = state.getSenderName();
              titleView.setText(sender != null ? sender : "");

              // Speed label
              float speed = state.getPlaybackSpeed();
              if (speed >= 1.9f) {
                speedButton.setText("2x");
              } else if (speed >= 1.4f) {
                speedButton.setText("1.5x");
              } else {
                speedButton.setText("1x");
              }

              // Progress
              long duration = state.getDuration();
              long position = state.getCurrentPosition();
              if (duration > 0) {
                progressBar.setProgress((int) (position * 10000L / duration));
              } else {
                progressBar.setProgress(0);
              }
            });

    playPauseButton.setOnClickListener(
        v -> {
          if (viewModel == null || currentMsgId < 0) return;
          AudioPlaybackState state = viewModel.getPlaybackState().getValue();
          if (state == null) return;
          if (state.getStatus() == AudioPlaybackState.PlaybackStatus.PLAYING) {
            viewModel.pause(currentMsgId);
          } else {
            viewModel.play(currentMsgId);
          }
        });

    speedButton.setOnClickListener(
        v -> {
          if (viewModel != null) {
            viewModel.cyclePlaybackSpeed();
          }
        });

    closeButton.setOnClickListener(
        v -> {
          if (viewModel != null && currentMsgId >= 0) {
            viewModel.stop(currentMsgId);
          }
        });
  }
}
