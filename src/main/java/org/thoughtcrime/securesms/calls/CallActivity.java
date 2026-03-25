package org.thoughtcrime.securesms.calls;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.WebViewActivity;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.Util;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import chat.delta.rpc.Rpc;
import chat.delta.rpc.RpcException;

public class CallActivity extends WebViewActivity implements DcEventCenter.DcEventDelegate {
  private static final String TAG = CallActivity.class.getSimpleName();

  public static final String EXTRA_ACCOUNT_ID = "acc_id";
  public static final String EXTRA_CHAT_ID = "chat_id";
  public static final String EXTRA_CALL_ID = "call_id";
  public static final String EXTRA_HASH = "hash";
  public static final String EXTRA_HAS_VIDEO = "has_video";

  private DcContext dcContext;
  private Rpc rpc;
  private int accId;
  private int chatId;
  private volatile int callId;
  private boolean hasVideo;
  private boolean ended = false;
  private View overlayInfoArea;
  private View pulseRing;
  private ImageView overlayAvatar;
  private TextView overlayNameView;
  private TextView overlayStatusView;
  private ImageButton btnAnswer, btnSpeaker, btnMic, btnCamera;
  private AnimatorSet pulseAnimSet;
  private boolean speakerEnabled = false;
  private final Handler timerHandler = new Handler(Looper.getMainLooper());
  private Runnable timerRunnable;
  private int callSecsElapsed = 0;

  private void hideNavigationBar() {
    getWindow().getDecorView().setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE
      | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
      | View.SYSTEM_UI_FLAG_FULLSCREEN
      | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    );
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
      hideNavigationBar();
      applySpeakerState();
    }
  }

  private void applySpeakerState() {
    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    if (am == null) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      int targetType = speakerEnabled ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
      for (AudioDeviceInfo device : am.getAvailableCommunicationDevices()) {
        if (device.getType() == targetType) {
          am.setCommunicationDevice(device);
          return;
        }
      }
    } else {
      am.setSpeakerphoneOn(speakerEnabled);
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  @Override
  protected void onCreate(Bundle state, boolean ready) {
    super.onCreate(state, ready);
    if (getSupportActionBar() != null) getSupportActionBar().hide();
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    hideNavigationBar();
    ViewGroup overlayRoot = (ViewGroup) webView.getParent().getParent();
    View callOverlay = LayoutInflater.from(this).inflate(R.layout.call_overlay, overlayRoot, false);
    overlayRoot.addView(callOverlay);
    overlayNameView = callOverlay.findViewById(R.id.overlay_name);
    overlayStatusView = callOverlay.findViewById(R.id.overlay_status);
    overlayInfoArea = callOverlay.findViewById(R.id.overlay_info_area);
    overlayAvatar = callOverlay.findViewById(R.id.overlay_avatar);
    pulseRing = callOverlay.findViewById(R.id.pulse_ring);
    btnAnswer = callOverlay.findViewById(R.id.btn_answer);
    btnSpeaker = callOverlay.findViewById(R.id.btn_speaker);
    btnMic = callOverlay.findViewById(R.id.btn_mic);
    btnCamera = callOverlay.findViewById(R.id.btn_camera);
    ImageButton btnEnd = callOverlay.findViewById(R.id.btn_end);
    btnEnd.setOnClickListener(v -> finish());
    btnSpeaker.setOnClickListener(v -> {
      speakerEnabled = !speakerEnabled;
      btnSpeaker.setImageResource(speakerEnabled ? R.drawable.ic_speaker_on : R.drawable.ic_speaker_off);
      applySpeakerState();
    });
    btnMic.setOnClickListener(v ->
      webView.evaluateJavascript("window.__nativeToggleMic&&window.__nativeToggleMic()", null));
    btnCamera.setOnClickListener(v ->
      webView.evaluateJavascript("window.__nativeToggleVideo&&window.__nativeToggleVideo()", null));
    btnAnswer.setOnClickListener(v ->
      webView.evaluateJavascript("window.__nativeAcceptCall&&window.__nativeAcceptCall()", null));

    Bundle bundle = getIntent().getExtras();
    if (bundle == null) { finish(); return; }
    hasVideo = bundle.getBoolean(EXTRA_HAS_VIDEO, true);
    String hash = bundle.getString(EXTRA_HASH, "");
    String query = hasVideo? "" : "?noOutgoingVideoInitially";
    accId = bundle.getInt(EXTRA_ACCOUNT_ID, -1);
    chatId = bundle.getInt(EXTRA_CHAT_ID, 0);
    callId = bundle.getInt(EXTRA_CALL_ID, 0);
    rpc = DcHelper.getRpc(this);
    dcContext = DcHelper.getAccounts(this).getAccount(accId);

    startPulseAnimation();
    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    if (am != null) am.setMode(AudioManager.MODE_IN_COMMUNICATION);

    DcHelper.getNotificationCenter(this).removeCallNotification(accId, callId);

    WebSettings webSettings = webView.getSettings();
    webSettings.setJavaScriptEnabled(true);
    webSettings.setMediaPlaybackRequiresUserGesture(false);
    webView.addJavascriptInterface(new InternalJSApi(this), "calls");

    webView.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onPermissionRequest(PermissionRequest request) {
      Util.runOnMain(() -> request.grant(request.getResources()));
      }
    });

    DcEventCenter eventCenter = DcHelper.getEventCenter(this);
    eventCenter.addObserver(DcContext.DC_EVENT_OUTGOING_CALL_ACCEPTED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_CALL_ENDED, this);

    Permissions.with(this)
      .request(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
      .ifNecessary()
      .withPermanentDenialDialog(getString(R.string.perm_explain_access_to_camera_denied))
      .onAllGranted(() -> {
        String url = "file:///android_asset/calls/index.html";
        Util.runOnAnyBackgroundThread(() -> {
          String cname = "";
          Bitmap avatarBmp = null;
          try {
            DcChat dcChat = dcContext.getChat(chatId);
            String n = dcChat.getName();
            if (n != null) cname = n;
            String imageFile = dcChat.getProfileImage();
            if (!TextUtils.isEmpty(imageFile)) {
              Bitmap raw = BitmapFactory.decodeFile(imageFile);
              if (raw != null) {
                avatarBmp = toCircularBitmap(raw);
                raw.recycle();
              }
            }
          } catch (Exception e) {
            Log.e(TAG, "get chat name/avatar error", e);
          }
          final String nameFinal = cname;
          final String nameParam = "name=" + Uri.encode(cname);
          final String finalQuery = query.isEmpty() ? "?" + nameParam : query + "&" + nameParam;
          final Bitmap bmpFinal = avatarBmp;
          Util.runOnMain(() -> {
            if (isDestroyed()) {
              if (bmpFinal != null) bmpFinal.recycle();
              return;
            }
            if (overlayNameView != null) overlayNameView.setText(nameFinal);
            if (overlayAvatar != null && bmpFinal != null) overlayAvatar.setImageBitmap(bmpFinal);
            webView.loadUrl(url + finalQuery + hash);
          });
        });
      }).onAnyDenied(this::finish)
      .execute();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Override
  protected void onDestroy() {
    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    if (am != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        am.clearCommunicationDevice();
      } else {
        am.setSpeakerphoneOn(false);
      }
      am.setMode(AudioManager.MODE_NORMAL);
    }
    DcHelper.getEventCenter(this).removeObservers(this);
    if (callId != 0 && !ended) {
      final int endAccId = accId;
      final int endCallId = callId;
      Util.runOnAnyBackgroundThread(() -> {
        try {
          rpc.endCall(endAccId, endCallId);
        } catch (RpcException e) {
          Log.e(TAG, "Error", e);
        }
      });
    }
    if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
    stopPulseAnimation();
    super.onDestroy();
  }

  private void startPulseAnimation() {
    if (pulseRing == null) return;
    if (pulseAnimSet != null && pulseAnimSet.isRunning()) return;
    ObjectAnimator scaleX = ObjectAnimator.ofFloat(pulseRing, "scaleX", 1f, 1.6f);
    ObjectAnimator scaleY = ObjectAnimator.ofFloat(pulseRing, "scaleY", 1f, 1.6f);
    ObjectAnimator alpha = ObjectAnimator.ofFloat(pulseRing, "alpha", 0.65f, 0f);
    for (ObjectAnimator a : new ObjectAnimator[]{scaleX, scaleY, alpha}) {
      a.setDuration(1400);
      a.setRepeatCount(ValueAnimator.INFINITE);
      a.setRepeatMode(ValueAnimator.RESTART);
    }
    pulseAnimSet = new AnimatorSet();
    pulseAnimSet.playTogether(scaleX, scaleY, alpha);
    pulseAnimSet.start();
  }

  private void stopPulseAnimation() {
    if (pulseAnimSet != null) {
      pulseAnimSet.cancel();
      pulseAnimSet = null;
    }
    if (pulseRing != null) {
      pulseRing.setAlpha(0f);
      pulseRing.setScaleX(1f);
      pulseRing.setScaleY(1f);
    }
  }

  private static Bitmap toCircularBitmap(Bitmap src) {
    int size = Math.min(src.getWidth(), src.getHeight());
    Bitmap scaled = Bitmap.createScaledBitmap(src, size, size, true);
    Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(output);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
    if (scaled != src) scaled.recycle();
    return output;
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    // do not call super.onPrepareOptionsMenu() as the default "Search" menu is not needed
    return true;
  }

  @Override
  protected boolean openOnlineUrl(String url) {
    finish();
    return true;
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    switch (event.getId()) {
    case DcContext.DC_EVENT_OUTGOING_CALL_ACCEPTED:
      if (event.getData1Int() == callId) {
        try {
          String base64 = Base64.encodeToString(event.getData2Str().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
          String hash = "#onAnswer=" + URLEncoder.encode(base64, "UTF-8");
          webView.evaluateJavascript("window.location.hash = `"+hash+"`", null);
        } catch (UnsupportedEncodingException e) {
          throw new AssertionError(e); // UTF-8 is always available
        }
      }
      break;
    case DcContext.DC_EVENT_CALL_ENDED:
      if (event.getData1Int() == callId) {
        ended = true;
        finish();
      }
      break;
    }
  }


  static class InternalJSApi {
    private final WeakReference<CallActivity> activityRef;

    InternalJSApi(CallActivity activity) {
      activityRef = new WeakReference<>(activity);
    }

    @JavascriptInterface
    public String getIceServers() {
      CallActivity a = activityRef.get();
      if (a == null) return null;
      try {
        return a.rpc.iceServers(a.accId);
      } catch (RpcException e) {
        Log.e(TAG, "Error", e);
        return null;
      }
    }

    @JavascriptInterface
    public void startCall(String payload) {
      CallActivity a = activityRef.get();
      if (a == null) return;
      try {
        a.callId = a.rpc.placeOutgoingCall(a.accId, a.chatId, payload, a.hasVideo);
      } catch (RpcException e) {
        Log.e(TAG, "Error", e);
      }
    }

    @JavascriptInterface
    public void acceptCall(String payload) {
      CallActivity a = activityRef.get();
      if (a == null) return;
      try {
        a.rpc.acceptIncomingCall(a.accId, a.callId, payload);
      } catch (RpcException e) {
        Log.e(TAG, "Error", e);
      }
    }

    @JavascriptInterface
    public void endCall() {
      CallActivity a = activityRef.get();
      if (a == null) return;
      a.finish();
    }

    @JavascriptInterface
    public void notifyCallState(String state) {
      CallActivity a = activityRef.get();
      if (a == null) return;
      Util.runOnMain(() -> {
        if (a.isDestroyed()) return;
        if (a.timerRunnable != null) {
          a.timerHandler.removeCallbacks(a.timerRunnable);
          a.timerRunnable = null;
        }
        if (a.overlayStatusView == null) return;
        switch (state) {
          case "ringing":
            a.overlayStatusView.setText(a.getString(R.string.call_status_ringing));
            a.startPulseAnimation();
            break;
          case "connecting":
            a.overlayStatusView.setText(a.getString(R.string.call_status_connecting));
            a.startPulseAnimation();
            break;
          case "in-call":
            a.stopPulseAnimation();
            if (a.btnAnswer != null) a.btnAnswer.setVisibility(View.GONE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              a.applySpeakerState();
            } else {
              a.timerHandler.postDelayed(a::applySpeakerState, 300);
            }
            a.callSecsElapsed = 0;
            a.timerRunnable = new Runnable() {
              @Override
              public void run() {
                if (a.isDestroyed()) return;
                int mm = a.callSecsElapsed / 60;
                int ss = a.callSecsElapsed % 60;
                a.overlayStatusView.setText(String.format(java.util.Locale.US, "%02d:%02d", mm, ss));
                a.callSecsElapsed++;
                a.timerHandler.postDelayed(this, 1000);
              }
            };
            a.overlayStatusView.setText("00:00");
            a.timerHandler.postDelayed(a.timerRunnable, 1000);
            break;
          case "promptingUserToAcceptCall":
            a.overlayStatusView.setText(a.getString(R.string.call_status_incoming));
            if (a.btnAnswer != null) a.btnAnswer.setVisibility(View.VISIBLE);
            a.startPulseAnimation();
            break;
        }
      });
    }

    @JavascriptInterface
    public void notifyVideoActive(boolean active) {
      CallActivity a = activityRef.get();
      if (a == null) return;
      Util.runOnMain(() -> {
        if (a.isDestroyed()) return;
        if (a.overlayInfoArea != null) {
          a.overlayInfoArea.setVisibility(active ? View.GONE : View.VISIBLE);
        }
      });
    }

    @JavascriptInterface
    public void notifyMicState(boolean enabled) {
      CallActivity a = activityRef.get();
      if (a == null) return;
      Util.runOnMain(() -> {
        if (a.isDestroyed()) return;
        if (a.btnMic != null) a.btnMic.setImageResource(enabled ? R.drawable.ic_mic_on : R.drawable.ic_mic_off);
      });
    }

    @JavascriptInterface
    public void notifyVideoState(boolean enabled) {
      CallActivity a = activityRef.get();
      if (a == null) return;
      Util.runOnMain(() -> {
        if (a.isDestroyed()) return;
        if (a.btnCamera != null) a.btnCamera.setImageResource(enabled ? R.drawable.ic_videocam_on : R.drawable.ic_videocam_off);
      });
    }
  }
}
