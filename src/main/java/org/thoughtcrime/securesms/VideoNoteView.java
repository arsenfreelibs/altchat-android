package org.thoughtcrime.securesms;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcMsg;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.thoughtcrime.securesms.components.CircleFrameLayout;
import org.thoughtcrime.securesms.components.ConversationItemFooter;
import org.thoughtcrime.securesms.components.VideoNoteProgressRing;
import org.thoughtcrime.securesms.components.audioplay.AudioPlaybackViewModel;
import org.thoughtcrime.securesms.components.audioplay.AudioView;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.ViewUtil;

public class VideoNoteView extends FrameLayout implements BindableConversationItem {

  private static final String TAG = VideoNoteView.class.getSimpleName();
  private static final int PROGRESS_UPDATE_MS = 50;
  private static final int FRAME_COPY_MS = 50;
  private static final int MAX_FRAME_DECODE_SIZE_PX = 360;
  private static final ExecutorService FRAME_DECODE_EXECUTOR =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "VideoNoteFrameDecoder");
            thread.setDaemon(true);
            return thread;
          });

  private DcMsg messageRecord;
  private @Nullable
  ExoPlayer player;
  private boolean playing = false;
  private final Handler progressHandler = new Handler(Looper.getMainLooper());
  private @Nullable
  Runnable progressRunnable;

  private android.widget.FrameLayout circleContainer;
  private ImageView videoFrameView;
  private ImageView thumbnailView;
  private ImageView playIcon;
  private VideoNoteProgressRing progressRing;
  private TextView senderNameView;
  private ConversationItemFooter footer;

  private @Nullable
  Thread thumbnailThread;
  // Singleton: at most one VideoNoteView plays at a time across the entire screen.
  @Nullable
  private static java.lang.ref.WeakReference<VideoNoteView> sCurrentlyPlaying = null;

  private @Nullable Runnable frameCopyRunnable;
  private @Nullable MediaMetadataRetriever frameRetriever;
  private @Nullable Bitmap currentFrameBitmap;
  private boolean frameDecodeInFlight = false;
  private int frameDecodeGeneration = 0;

  private @Nullable
  BindableConversationItem.EventListener eventListener;
  private @Nullable
  OnClickListener adapterClickListener;
  private @NonNull
  Set<DcMsg> batchSelected = new java.util.HashSet<>();

  public VideoNoteView(Context context) {
    super(context);
    init(context);
  }

  public VideoNoteView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init(context);
  }

  private void init(Context context) {
    LayoutInflater.from(context).inflate(R.layout.video_note_cell, this, true);

    circleContainer = findViewById(R.id.video_note_circle);
    videoFrameView = findViewById(R.id.video_note_frame);
    thumbnailView = findViewById(R.id.video_note_thumbnail);

    // Samsung does not reliably clip live TextureView video inside a circle.
    // We render decoded frames into videoFrameView instead.

    playIcon = findViewById(R.id.video_note_play_icon);
    progressRing = findViewById(R.id.video_note_progress_ring);
    senderNameView = findViewById(R.id.video_note_sender_name);
    footer = findViewById(R.id.video_note_footer);

    progressRing.configureAsPlaybackRing();

    // Internal click: tap to play/pause, or pass to adapter in batch-select mode.
    super.setOnClickListener(
      v -> {
        if (!batchSelected.isEmpty()) {
          if (adapterClickListener != null) adapterClickListener.onClick(v);
        } else {
          togglePlayback();
        }
      });
  }

  @Override
  public void setOnClickListener(@Nullable OnClickListener l) {
    this.adapterClickListener = l;
    // don't call super — internal click handler already set in init()
  }

  @Override
  public void bind(
    @NonNull DcMsg messageRecord,
    @NonNull DcChat dcChat,
    @NonNull GlideRequests glideRequests,
    @NonNull Set<DcMsg> batchSelected,
    @NonNull Recipient recipients,
    boolean pulseHighlight,
    @Nullable AudioPlaybackViewModel playbackViewModel,
    AudioView.OnActionListener audioPlayPauseListener) {
    this.messageRecord = messageRecord;
    this.batchSelected = batchSelected;

    // Position circle and footer: right for outgoing, left for incoming
    int dp8 = (int) (8 * getResources().getDisplayMetrics().density);
    int dp16 = dp8 * 2;

    android.widget.FrameLayout.LayoutParams lp =
      (android.widget.FrameLayout.LayoutParams) circleContainer.getLayoutParams();
    android.widget.FrameLayout.LayoutParams flp =
      (android.widget.FrameLayout.LayoutParams) footer.getLayoutParams();
    if (messageRecord.isOutgoing()) {
      lp.gravity = android.view.Gravity.END;
      lp.rightMargin = dp8;
      lp.leftMargin = 0;
      flp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
      flp.rightMargin = dp16;
      flp.leftMargin = 0;
    } else {
      lp.gravity = android.view.Gravity.START;
      lp.leftMargin = dp8;
      lp.rightMargin = 0;
      flp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
      flp.leftMargin = dp16;
      flp.rightMargin = 0;
    }
    circleContainer.setLayoutParams(lp);
    footer.setLayoutParams(flp);

    // Load thumbnail with rotation
    loadThumbnailAsync(messageRecord.getFile());

    // Sender name (groups / forwarded)
    DcContact contact = DcHelper.getContext(getContext()).getContact(messageRecord.getFromId());
    boolean isGroup = dcChat.isMultiUser();
    boolean isOutgoing = messageRecord.isOutgoing();
    boolean isForwarded = messageRecord.isForwarded();

    if (!isOutgoing && (isGroup || isForwarded)) {
      if (isForwarded) {
        senderNameView.setText(getContext().getString(R.string.forwarded_message));
      } else {
        senderNameView.setText(contact.getDisplayName());
      }
      senderNameView.setVisibility(View.VISIBLE);
    } else {
      senderNameView.setVisibility(View.GONE);
    }

    footer.setMessageRecord(messageRecord);

    // Reset playback state on rebind
    stopPlayback();
    thumbnailView.setVisibility(View.VISIBLE);
    playIcon.setVisibility(View.VISIBLE);
    progressRing.setProgress(0f);
    progressRing.setVisibility(View.INVISIBLE);

    if (pulseHighlight) {
      setAlpha(0.5f);
      animate().alpha(1f).setDuration(500L).start();
    } else {
      setAlpha(1f);
    }
  }

  @Override
  public DcMsg getMessageRecord() {
    return messageRecord;
  }

  @Override
  public void setEventListener(@Nullable BindableConversationItem.EventListener listener) {
    this.eventListener = listener;
  }

  @Override
  public void unbind() {
    stopPlayback();
    if (thumbnailThread != null) {
      thumbnailThread.interrupt();
      thumbnailThread = null;
    }
  }

  private void loadThumbnailAsync(@Nullable String filePath) {
    if (thumbnailThread != null) thumbnailThread.interrupt();
    thumbnailView.setImageBitmap(null);
    if (filePath == null || filePath.isEmpty()) return;

    thumbnailThread =
      new Thread(
        () -> {
          Bitmap frame = null;
          try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
            mmr.setDataSource(filePath);
            frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            // Do NOT apply rotation manually — getFrameAtTime() already returns
            // a correctly-oriented frame on API 27+ (Android 8.1+)
          } catch (Exception e) {
            Log.e(TAG, "Thumbnail load error", e);
          }
          if (Thread.currentThread().isInterrupted()) return;
          final Bitmap result = frame;
          post(() -> thumbnailView.setImageBitmap(result));
        });
    thumbnailThread.setDaemon(true);
    thumbnailThread.start();
  }

  private void togglePlayback() {
    if (playing) {
      pausePlayback();
    } else {
      startPlayback();
    }
    // Spring scale animation
    animate()
      .scaleX(1.06f)
      .scaleY(1.06f)
      .setDuration(80)
      .withEndAction(
        () ->
          animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(80)
            .start())
      .start();
  }

  private void startPlayback() {
    if (messageRecord == null) return;
    final String filePath = messageRecord.getFile();
    if (filePath == null || filePath.isEmpty()) return;

    // Stop any other currently playing VideoNoteView first.
    VideoNoteView prev = sCurrentlyPlaying != null ? sCurrentlyPlaying.get() : null;
    if (prev != null && prev != this) {
      prev.stopPlayback();
    }
    sCurrentlyPlaying = new java.lang.ref.WeakReference<>(this);

    // Release previous player instance if any (e.g. replay after video ended).
    if (player != null) {
      player.stop();
      player.release();
      player = null;
    }

    player =
      new ExoPlayer.Builder(getContext())
        .setAudioAttributes(
          new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
          true)
        .build();

    player.addListener(
      new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
          if (state == Player.STATE_ENDED) {
            onVideoEnded();
          }
        }
      });

    playing = true;
    // Keep thumbnail visible until the first decoded frame arrives.
    playIcon.setVisibility(View.GONE);
    progressRing.setVisibility(View.VISIBLE);
    startProgressUpdates();

    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new java.io.File(filePath))));
    player.prepare();
    player.play();
    startFrameCapture(filePath);
  }

  private void pausePlayback() {
    if (player != null) player.pause();
    playing = false;
    stopProgressUpdates();
    stopFrameCapture();
    thumbnailView.setVisibility(View.VISIBLE);
    playIcon.setVisibility(View.VISIBLE);
    progressRing.setVisibility(View.INVISIBLE);
    VideoNoteView cur = sCurrentlyPlaying != null ? sCurrentlyPlaying.get() : null;
    if (cur == this) sCurrentlyPlaying = null;
  }

  private void stopPlayback() {
    stopProgressUpdates();
    stopFrameCapture();
    if (player != null) {
      player.stop();
      player.release();
      player = null;
    }
    playing = false;
    if (thumbnailView != null) thumbnailView.setVisibility(View.VISIBLE);
    if (playIcon != null) playIcon.setVisibility(View.VISIBLE);
    if (progressRing != null) {
      progressRing.setProgress(0f);
      progressRing.setVisibility(View.INVISIBLE);
    }
    VideoNoteView cur = sCurrentlyPlaying != null ? sCurrentlyPlaying.get() : null;
    if (cur == this) sCurrentlyPlaying = null;
  }

  private void onVideoEnded() {
    playing = false;
    stopProgressUpdates();
    stopFrameCapture();
    if (player != null) {
      player.pause();
      player.seekTo(0);
    }
    thumbnailView.setVisibility(View.VISIBLE);
    playIcon.setVisibility(View.VISIBLE);
    progressRing.setProgress(0f);
    progressRing.setVisibility(View.INVISIBLE);
    VideoNoteView cur = sCurrentlyPlaying != null ? sCurrentlyPlaying.get() : null;
    if (cur == this) sCurrentlyPlaying = null;
  }

  // ---- Frame capture: decode video frames to a regular ImageView ----

  private void startFrameCapture(@NonNull String filePath) {
    stopFrameCapture();

    final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      retriever.setDataSource(filePath);
    } catch (RuntimeException e) {
      Log.e(TAG, "Frame retriever init failed", e);
      return;
    }

    frameRetriever = retriever;
    final int generation = ++frameDecodeGeneration;
    final int sourceWidth = parseMetadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
    final int sourceHeight = parseMetadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
    final int rotationDegrees = parseMetadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
    final boolean swapSourceAxes = rotationDegrees == 90 || rotationDegrees == 270;

    frameCopyRunnable = new Runnable() {
      @Override
      public void run() {
        if (!playing || player == null || frameRetriever != retriever || generation != frameDecodeGeneration) {
          return;
        }

        if (frameDecodeInFlight) {
          progressHandler.postDelayed(this, FRAME_COPY_MS / 2L);
          return;
        }

        frameDecodeInFlight = true;
        final long positionMs = Math.max(0L, player.getCurrentPosition());
        int targetWidth = Math.max(videoFrameView.getWidth(), circleContainer.getWidth());
        int targetHeight = Math.max(videoFrameView.getHeight(), circleContainer.getHeight());
        if (targetWidth <= 0 && circleContainer.getLayoutParams() != null) {
          targetWidth = circleContainer.getLayoutParams().width;
        }
        if (targetHeight <= 0 && circleContainer.getLayoutParams() != null) {
          targetHeight = circleContainer.getLayoutParams().height;
        }
        final int decodeWidth = clampFrameDecodeSize(targetWidth);
        final int decodeHeight = clampFrameDecodeSize(targetHeight);
        final long decodeStartedAtMs = SystemClock.uptimeMillis();

        FRAME_DECODE_EXECUTOR.execute(
            () -> {
              final Bitmap bitmap =
                  decodeFrameAt(
                      retriever,
                      positionMs,
                      decodeWidth,
                      decodeHeight,
                      sourceWidth,
                      sourceHeight,
                      swapSourceAxes);
              post(
                  () -> {
                    frameDecodeInFlight = false;

                    if (generation != frameDecodeGeneration || frameRetriever != retriever || !playing) {
                      recycleBitmap(bitmap);
                      releaseRetriever(retriever);
                      return;
                    }

                    if (bitmap != null) {
                      setVideoFrame(bitmap);
                      if (videoFrameView.getVisibility() != View.VISIBLE) {
                        videoFrameView.setVisibility(View.VISIBLE);
                        thumbnailView.setVisibility(View.GONE);
                      }
                    }

                    if (playing && frameCopyRunnable != null && generation == frameDecodeGeneration) {
                      long elapsedMs = SystemClock.uptimeMillis() - decodeStartedAtMs;
                      long nextDelayMs = Math.max(0L, FRAME_COPY_MS - elapsedMs);
                      progressHandler.postDelayed(frameCopyRunnable, nextDelayMs);
                    }
                  });
            });
      }
    };

    progressHandler.post(frameCopyRunnable);
  }

  private static int clampFrameDecodeSize(int size) {
    if (size <= 0) return size;
    return Math.min(size, MAX_FRAME_DECODE_SIZE_PX);
  }

  private static int parseMetadataInt(
      @NonNull MediaMetadataRetriever retriever, int metadataKey) {
    try {
      String value = retriever.extractMetadata(metadataKey);
      if (value == null || value.isEmpty()) return 0;
      return Integer.parseInt(value);
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private @Nullable Bitmap decodeFrameAt(
      @NonNull MediaMetadataRetriever retriever,
      long positionMs,
      int targetWidth,
      int targetHeight,
      int sourceWidth,
      int sourceHeight,
      boolean swapSourceAxes) {
    final long timeUs = positionMs * 1000L;
    try {
      if (Build.VERSION.SDK_INT >= 27 && targetWidth > 0 && targetHeight > 0) {
        int orientedSourceWidth = swapSourceAxes ? sourceHeight : sourceWidth;
        int orientedSourceHeight = swapSourceAxes ? sourceWidth : sourceHeight;

        if (orientedSourceWidth > 0 && orientedSourceHeight > 0) {
          float scale =
              Math.max(
                  targetWidth / (float) orientedSourceWidth,
                  targetHeight / (float) orientedSourceHeight);
          int scaledWidth = Math.max(1, Math.round(orientedSourceWidth * scale));
          int scaledHeight = Math.max(1, Math.round(orientedSourceHeight * scale));

          Bitmap scaled =
              retriever.getScaledFrameAtTime(
                  timeUs, MediaMetadataRetriever.OPTION_CLOSEST, scaledWidth, scaledHeight);
          if (scaled != null) {
            return scaled;
          }
        }
      }

      Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
      if (frame == null) return null;

      if (targetWidth <= 0 || targetHeight <= 0) {
        return frame;
      }

      float scale =
          Math.max(targetWidth / (float) frame.getWidth(), targetHeight / (float) frame.getHeight());
      int scaledWidth = Math.max(1, Math.round(frame.getWidth() * scale));
      int scaledHeight = Math.max(1, Math.round(frame.getHeight() * scale));

      if (frame.getWidth() == scaledWidth && frame.getHeight() == scaledHeight) {
        return frame;
      }

      Bitmap scaled = Bitmap.createScaledBitmap(frame, scaledWidth, scaledHeight, true);
      recycleBitmap(frame);
      return scaled;
    } catch (RuntimeException e) {
      Log.w(TAG, "Frame decode failed at " + positionMs + "ms", e);
      return null;
    }
  }

  private void setVideoFrame(@NonNull Bitmap bitmap) {
    Bitmap previous = currentFrameBitmap;
    currentFrameBitmap = bitmap;
    videoFrameView.setImageBitmap(bitmap);
    recycleBitmap(previous);
  }

  private void clearVideoFrame() {
    videoFrameView.setImageDrawable(null);
    recycleBitmap(currentFrameBitmap);
    currentFrameBitmap = null;
  }

  private void stopFrameCapture() {
    if (frameCopyRunnable != null) {
      progressHandler.removeCallbacks(frameCopyRunnable);
      frameCopyRunnable = null;
    }

    frameDecodeGeneration++;
    MediaMetadataRetriever retriever = frameRetriever;
    frameRetriever = null;

    if (!frameDecodeInFlight) {
      releaseRetriever(retriever);
    }

    if (videoFrameView != null) {
      videoFrameView.setVisibility(View.GONE);
      clearVideoFrame();
    }
  }

  private static void releaseRetriever(@Nullable MediaMetadataRetriever retriever) {
    if (retriever == null) return;
    try {
      retriever.release();
    } catch (Exception e) {
      Log.w(TAG, "Retriever release failed", e);
    }
  }

  private static void recycleBitmap(@Nullable Bitmap bitmap) {
    if (bitmap != null && !bitmap.isRecycled()) {
      bitmap.recycle();
    }
  }

  private void startProgressUpdates() {
    progressRunnable =
      new Runnable() {
        @Override
        public void run() {
          if (player != null && playing) {
            long duration = player.getDuration();
            long position = player.getCurrentPosition();
            if (duration > 0) {
              progressRing.setProgress((float) position / duration);
            }
            progressHandler.postDelayed(this, PROGRESS_UPDATE_MS);
          }
        }
      };
    progressHandler.post(progressRunnable);
  }

  private void stopProgressUpdates() {
    if (progressRunnable != null) {
      progressHandler.removeCallbacks(progressRunnable);
      progressRunnable = null;
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    stopPlayback();
  }
}
