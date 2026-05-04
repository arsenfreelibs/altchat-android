package org.thoughtcrime.securesms.components.audioplay;

import android.net.Uri;
import androidx.annotation.Nullable;

public class AudioPlaybackState {
  private final int msgId;
  private final @Nullable Uri audioUri;
  private final PlaybackStatus status;
  private final long currentPosition;
  private final long duration;
  private final @Nullable String senderName;
  private final float playbackSpeed;

  public enum PlaybackStatus {
    IDLE,
    LOADING,
    PLAYING,
    PAUSED,
    ERROR
  }

  public AudioPlaybackState(
      int msgId,
      @Nullable Uri audioUri,
      PlaybackStatus status,
      long currentPosition,
      long duration,
      @Nullable String senderName,
      float playbackSpeed) {
    this.msgId = msgId;
    this.audioUri = audioUri;
    this.status = status;
    this.currentPosition = currentPosition;
    this.duration = duration;
    this.senderName = senderName;
    this.playbackSpeed = playbackSpeed;
  }

  public static AudioPlaybackState idle() {
    return new AudioPlaybackState(0, null, PlaybackStatus.IDLE, 0, 0, null, 1.0f);
  }

  public int getMsgId() {
    return msgId;
  }

  @Nullable
  public Uri getAudioUri() {
    return audioUri;
  }

  public PlaybackStatus getStatus() {
    return status;
  }

  public long getCurrentPosition() {
    return currentPosition;
  }

  public long getDuration() {
    return duration;
  }

  @Nullable
  public String getSenderName() {
    return senderName;
  }

  public float getPlaybackSpeed() {
    return playbackSpeed;
  }
}
