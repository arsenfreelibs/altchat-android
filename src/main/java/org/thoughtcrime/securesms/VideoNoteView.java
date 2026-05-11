package org.thoughtcrime.securesms;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import chat.delta.rpc.RpcException;
import chat.delta.rpc.types.Reaction;
import chat.delta.rpc.types.Reactions;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcMsg;

import java.util.List;
import java.util.Set;

import org.thoughtcrime.securesms.components.ConversationItemFooter;
import org.thoughtcrime.securesms.components.VideoNoteProgressRing;
import org.thoughtcrime.securesms.components.audioplay.AudioPlaybackViewModel;
import org.thoughtcrime.securesms.components.audioplay.AudioView;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.reactions.ReactionsConversationView;
import org.thoughtcrime.securesms.recipients.Recipient;

public class VideoNoteView extends FrameLayout implements BindableConversationItem {

  private static final String TAG = VideoNoteView.class.getSimpleName();
  private static final int PROGRESS_UPDATE_MS = 50;

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
  private TextView durationView;
  private ConversationItemFooter footer;
  private ReactionsConversationView reactionsOutgoingView;
  private ReactionsConversationView reactionsIncomingView;

  private @Nullable
  Thread thumbnailThread;
  // Singleton: at most one VideoNoteView plays at a time across the entire screen.
  @Nullable
  private static java.lang.ref.WeakReference<VideoNoteView> sCurrentlyPlaying = null;

  private int thumbnailGeneration = 0;
  private TextureView textureView;

  private @Nullable
  BindableConversationItem.EventListener eventListener;
  private @Nullable
  OnClickListener adapterClickListener;
  private float lastTapX = Float.NaN;
  private float lastTapY = Float.NaN;
  private final Runnable clearPulseSelectionRunnable = () -> setSelected(false);
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

    textureView = findViewById(R.id.video_note_texture);
    // TextureView is a sibling of CircleFrameLayout in the root FrameLayout.
    // setClipToOutline clips it to a circle at the HWUI compositor level.
    textureView.setOutlineProvider(new ViewOutlineProvider() {
      @Override
      public void getOutline(View view, Outline outline) {
        outline.setOval(0, 0, view.getWidth(), view.getHeight());
      }
    });
    textureView.setClipToOutline(true);

    playIcon = findViewById(R.id.video_note_play_icon);
    progressRing = findViewById(R.id.video_note_progress_ring);
    senderNameView = findViewById(R.id.video_note_sender_name);
    durationView = findViewById(R.id.video_note_duration);
    footer = findViewById(R.id.video_note_footer);
    reactionsOutgoingView = findViewById(R.id.video_note_reactions_outgoing);
    reactionsIncomingView = findViewById(R.id.video_note_reactions_incoming);

    progressRing.configureAsPlaybackRing();
    setBackgroundResource(R.drawable.conversation_item_background);

    // Track touch point so click can distinguish circle taps from cell taps.
    super.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
              lastTapX = event.getX();
              lastTapY = event.getY();
              break;
            case android.view.MotionEvent.ACTION_CANCEL:
              lastTapX = Float.NaN;
              lastTapY = Float.NaN;
              break;
            default:
              break;
          }
          return false;
        });

    // In normal mode: circle tap toggles playback, outside-circle tap opens adapter context.
    // In batch-select mode: any tap is passed to adapter selection handling.
    super.setOnClickListener(
        v -> {
          if (!batchSelected.isEmpty()) {
            if (adapterClickListener != null) {
              adapterClickListener.onClick(v);
            }
            return;
          }

          if (isLastTapInsideCircle()) {
            togglePlayback();
          } else if (adapterClickListener != null) {
            adapterClickListener.onClick(v);
          }
        }
    );
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
    applyInteractionState(messageRecord, pulseHighlight);

    // Position circle: right for outgoing, left for incoming.
    // Footer always appears at bottom-right of the circle (same visual position for both directions).
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
      flp.bottomMargin = dp8;
    } else {
      lp.gravity = android.view.Gravity.START;
      lp.leftMargin = dp8;
      lp.rightMargin = 0;
      // For incoming: circle right edge = 8dp + 200dp = 208dp.
      // Position footer so its right edge is at 200dp (= circle right edge - 8dp),
      // matching the same 8dp inset as for outgoing messages.
      // We measure the footer after setMessageRecord() to know its width.
      flp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
      flp.rightMargin = 0;
      flp.bottomMargin = dp8;
      // Measurement happens below after footer.setMessageRecord().
    }
    circleContainer.setLayoutParams(lp);

    // Sync TextureView position with circle container (it's a sibling in root FrameLayout)
    android.widget.FrameLayout.LayoutParams tvlp =
        (android.widget.FrameLayout.LayoutParams) textureView.getLayoutParams();
    tvlp.gravity = lp.gravity;
    tvlp.leftMargin = lp.leftMargin;
    tvlp.rightMargin = lp.rightMargin;
    textureView.setLayoutParams(tvlp);

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

    if (!messageRecord.isOutgoing()) {
      // Measure the footer now that its content is set, then position its right edge
      // at the same 8dp inset from the circle's right edge (= 200dp from screen left).
      footer.measure(
          android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
          android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
      int circleRightEdgePx = (int) ((8 + 200) * getResources().getDisplayMetrics().density);
      int insetPx = dp8;
      flp.leftMargin = circleRightEdgePx - insetPx - footer.getMeasuredWidth();
    }
    footer.setLayoutParams(flp);

    // Duration pill layout params — content loaded async in loadThumbnailAsync
    android.widget.FrameLayout.LayoutParams dlp =
        (android.widget.FrameLayout.LayoutParams) durationView.getLayoutParams();
    dlp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
    dlp.bottomMargin = dp8;
    dlp.rightMargin = 0;
    if (messageRecord.isOutgoing()) {
      int screenWidthPx = getResources().getDisplayMetrics().widthPixels;
      dlp.leftMargin = screenWidthPx - (int) (200 * getResources().getDisplayMetrics().density);
    } else {
      dlp.leftMargin = dp16;
    }
    durationView.setLayoutParams(dlp);
    durationView.setVisibility(View.GONE);

    setReactions(messageRecord);

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

  private void applyInteractionState(@NonNull DcMsg messageRecord, boolean pulseHighlight) {
    removeCallbacks(clearPulseSelectionRunnable);
    if (batchSelected.contains(messageRecord)) {
      setBackgroundResource(R.drawable.conversation_item_background);
      setSelected(true);
    } else if (pulseHighlight) {
      setBackgroundResource(R.drawable.conversation_item_background_animated);
      setSelected(true);
      postDelayed(clearPulseSelectionRunnable, 500L);
    } else {
      setBackgroundResource(R.drawable.conversation_item_background);
      setSelected(false);
    }
  }

  private boolean isLastTapInsideCircle() {
    if (Float.isNaN(lastTapX) || Float.isNaN(lastTapY)) {
      // Non-touch click path (e.g. accessibility): preserve previous play/pause default.
      return true;
    }

    return lastTapX >= circleContainer.getLeft()
        && lastTapX <= circleContainer.getRight()
        && lastTapY >= circleContainer.getTop()
        && lastTapY <= circleContainer.getBottom();
  }

  private void setReactions(@NonNull DcMsg current) {
    ReactionsConversationView active =
        current.isOutgoing() ? reactionsOutgoingView : reactionsIncomingView;
    ReactionsConversationView inactive =
        current.isOutgoing() ? reactionsIncomingView : reactionsOutgoingView;

    inactive.clear();
    inactive.setVisibility(View.GONE);
    active.setVisibility(View.VISIBLE);

    try {
      Reactions reactions =
          DcHelper.getRpc(getContext())
              .getMessageReactions(DcHelper.getContext(getContext()).getAccountId(), current.getId());
      List<Reaction> reactionList = reactions != null ? reactions.reactions : null;
      if (reactionList == null) {
        active.clear();
        updateBottomMetaOffset(false);
      } else {
        active.setReactions(reactionList);
        updateBottomMetaOffset(!reactionList.isEmpty());
        active.setOnClickListener(
            view -> {
              if (eventListener != null && batchSelected.isEmpty()) {
                eventListener.onReactionClicked(current);
              } else if (adapterClickListener != null) {
                adapterClickListener.onClick(VideoNoteView.this);
              } else {
                performClick();
              }
            });
      }
    } catch (RpcException e) {
      active.clear();
      updateBottomMetaOffset(false);
    }
  }

  private void updateBottomMetaOffset(boolean hasReactions) {
    int base = (int) (8 * getResources().getDisplayMetrics().density);
    int extra = hasReactions ? (int) (18 * getResources().getDisplayMetrics().density) : 0;

    FrameLayout.LayoutParams footerLp = (FrameLayout.LayoutParams) footer.getLayoutParams();
    footerLp.bottomMargin = base + extra;
    footer.setLayoutParams(footerLp);

    FrameLayout.LayoutParams durationLp = (FrameLayout.LayoutParams) durationView.getLayoutParams();
    durationLp.bottomMargin = base + extra;
    durationView.setLayoutParams(durationLp);
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

    final int generation = ++thumbnailGeneration;
    thumbnailThread =
      new Thread(
        () -> {
          Bitmap frame = null;
          long durationMs = 0;
          try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
            mmr.setDataSource(filePath);
            frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            // Do NOT apply rotation manually — getFrameAtTime() already returns
            // a correctly-oriented frame on API 27+ (Android 8.1+)
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null && !durStr.isEmpty()) {
              durationMs = Long.parseLong(durStr);
            }
          } catch (Exception e) {
            Log.e(TAG, "Thumbnail load error", e);
          }
          if (Thread.currentThread().isInterrupted()) return;
          final Bitmap result = frame;
          final long finalDuration = durationMs;
          post(() -> {
            if (generation != thumbnailGeneration) return;
            thumbnailView.setImageBitmap(result);
            if (finalDuration > 0) {
              int s = (int) (finalDuration / 1000);
              durationView.setText(String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60));
              durationView.setVisibility(View.VISIBLE);
            }
          });
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

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
          post(() -> applyCenterCropTransform(videoSize.width, videoSize.height));
        }

        @Override
        public void onRenderedFirstFrame() {
          post(() -> thumbnailView.setVisibility(View.INVISIBLE));
        }
      });

    playing = true;
    playIcon.setVisibility(View.GONE);
    progressRing.setVisibility(View.VISIBLE);
    startProgressUpdates();

    // ExoPlayer manages the SurfaceTextureListener internally.
    // Must be called BEFORE prepare()/play().
    player.setVideoTextureView(textureView);
    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new java.io.File(filePath))));
    player.prepare();
    player.play();
  }

  private void applyCenterCropTransform(int videoWidth, int videoHeight) {
    int vw = textureView.getWidth();
    int vh = textureView.getHeight();
    if (vw == 0 || vh == 0 || videoWidth == 0 || videoHeight == 0) return;
    // Scale so the video fills the square view (centerCrop): the larger dimension wins.
    float scaleX, scaleY;
    if ((float) videoWidth / videoHeight > (float) vw / vh) {
      // video wider than view — fit height, crop sides
      scaleX = ((float) videoWidth / videoHeight) * vh / vw;
      scaleY = 1f;
    } else {
      // video taller than view — fit width, crop top/bottom
      scaleX = 1f;
      scaleY = ((float) videoHeight / videoWidth) * vw / vh;
    }
    Matrix m = new Matrix();
    m.setScale(scaleX, scaleY, vw / 2f, vh / 2f);
    textureView.setTransform(m);
  }

  private void pausePlayback() {
    if (player != null) player.pause();
    playing = false;
    stopProgressUpdates();
    thumbnailView.setVisibility(View.VISIBLE);
    playIcon.setVisibility(View.VISIBLE);
    progressRing.setVisibility(View.INVISIBLE);
    VideoNoteView cur = sCurrentlyPlaying != null ? sCurrentlyPlaying.get() : null;
    if (cur == this) sCurrentlyPlaying = null;
  }

  private void stopPlayback() {
    stopProgressUpdates();
    textureView.setTransform(new Matrix());
    if (player != null) {
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
