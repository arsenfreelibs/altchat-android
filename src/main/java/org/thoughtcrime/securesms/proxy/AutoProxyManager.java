package org.thoughtcrime.securesms.proxy;

import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_PROXY_ENABLED;
import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_PROXY_URL;

import android.content.Context;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;
import android.os.HandlerThread;
import androidx.annotation.NonNull;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Random;
import javax.net.ssl.HttpsURLConnection;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Prefs;

/**
 * Automatically routes traffic through a bundled HTTP proxy when the account's relay (IMAP/SMTP
 * server) becomes unreachable while the internet otherwise works, and returns to a direct
 * connection once the relay is reachable again.
 *
 * <p>Port of the iOS {@code AutoProxyManager}. All state transitions and timers run on a single
 * background thread; connectivity events are re-posted onto that thread.
 */
public class AutoProxyManager implements DcEventCenter.DcEventDelegate {

  private static final String TAG = "AutoProxyManager";

  // --- Bundled proxies and timings (see ТЗ §3) ---
  // Proxy URLs are NOT stored in the repository. They come from the gitignored proxy.properties,
  // XOR+base64-obfuscated into BuildConfig at build time, and are decoded into memory here.
  // With an empty list (no proxy.properties) the manager stays disabled and never engages.
  private static final String[] PROXIES = decodeProxies();
  private static final long GRACE_MS = 20_000L; // relay must be down this long before engaging
  private static final long PROXY_TRY_MS = 30_000L; // time per proxy before rotating
  private static final long DIRECT_RECHECK_MS = 240_000L; // how often we re-test the direct relay
  private static final long BACKOFF_MS = 120_000L; // pause after a full unsuccessful round
  private static final int PROBE_TIMEOUT_MS = 8_000;
  private static final String INTERNET_PROBE_URL = "https://www.google.com/generate_204";

  private static final String PREF_AUTO_PROXY_ENGAGED = "pref_auto_proxy_engaged";

  private enum State {
    DIRECT,
    ENGAGING,
    ENGAGED
  }

  private final Context context;
  private final HandlerThread thread;
  private final Handler handler;
  private final Random random = new Random();

  private State state = State.DIRECT;
  private int proxyIdx = 0;
  private int triedCount = 0;
  private boolean graceScheduled = false;

  // Timer runnables kept as fields so they can be cancelled.
  private final Runnable graceTask = this::onGraceExpired;
  private final Runnable proxyTryTask = this::onProxyTryExpired;
  private final Runnable directRecheckTask = this::onDirectRecheck;
  private final Runnable backoffTask = this::onBackoffExpired;

  public AutoProxyManager(@NonNull Context context) {
    this.context = context.getApplicationContext();
    this.thread = new HandlerThread("AutoProxyManager");
    this.thread.start();
    this.handler = new Handler(thread.getLooper());
  }

  /** Called once after I/O has been started. Safe to call from any thread. */
  public void start() {
    DcHelper.getEventCenter(context).addObserver(DcContext.DC_EVENT_CONNECTIVITY_CHANGED, this);
    handler.post(this::onStart);
  }

  /** Optional hint to re-evaluate (e.g. on foreground / network change). */
  public void reevaluate() {
    handler.post(this::onConnectivityChanged);
  }

  // --- DcEventDelegate ---

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    if (event.getId() == DcContext.DC_EVENT_CONNECTIVITY_CHANGED) {
      handler.post(this::onConnectivityChanged);
    }
  }

  @Override
  public boolean runOnMain() {
    return false;
  }

  // --- State machine (all on the handler thread) ---

  private void onStart() {
    if (autoProxyEngaged()) {
      if (!hasProxies()) {
        // No bundled proxies available - undo any auto-engaged proxy and stay direct.
        disengage();
        state = State.DIRECT;
        return;
      }
      // We engaged in a previous session - resume in ENGAGED and keep re-checking the direct relay.
      Log.i(TAG, "resuming in ENGAGED state");
      state = State.ENGAGED;
      scheduleDirectRecheck();
    } else {
      state = State.DIRECT;
      onConnectivityChanged();
    }
  }

  private void onConnectivityChanged() {
    boolean connected = isRelayConnected();
    switch (state) {
      case DIRECT:
        if (connected) {
          cancelGrace();
        } else if (!graceScheduled) {
          scheduleGrace();
        }
        break;
      case ENGAGING:
        if (connected) {
          transitionToEngaged();
        }
        break;
      case ENGAGED:
        if (!connected) {
          // Proxy fell off - start the search over.
          Log.i(TAG, "proxy connection lost, re-engaging");
          startEngaging(randomProxyIndex());
        }
        break;
    }
  }

  private void onGraceExpired() {
    graceScheduled = false;
    if (state != State.DIRECT) return;
    if (isRelayConnected()) return; // recovered during grace
    if (!mayEngage()) return;

    if (hasInternet()) {
      Log.i(TAG, "relay down but internet up - engaging proxy");
      startEngaging(randomProxyIndex());
    } else {
      // General network outage - don't waste proxies, just keep watching.
      Log.i(TAG, "no internet - staying direct, rescheduling grace");
      scheduleGrace();
    }
  }

  private void startEngaging(int startIdx) {
    cancelAllTimers();
    state = State.ENGAGING;
    proxyIdx = startIdx;
    triedCount = 0;
    engageCurrentProxy();
    handler.postDelayed(proxyTryTask, PROXY_TRY_MS);
  }

  private void onProxyTryExpired() {
    if (state != State.ENGAGING) return;
    if (isRelayConnected()) {
      transitionToEngaged();
      return;
    }
    if (triedCount >= PROXIES.length) {
      // Tried them all without success - back off, then start a new round.
      Log.i(TAG, "all proxies failed, backing off " + (BACKOFF_MS / 1000) + "s");
      handler.postDelayed(backoffTask, BACKOFF_MS);
    } else {
      proxyIdx = (proxyIdx + 1) % PROXIES.length;
      engageCurrentProxy();
      handler.postDelayed(proxyTryTask, PROXY_TRY_MS);
    }
  }

  private void onBackoffExpired() {
    if (state != State.ENGAGING) return;
    startEngaging(randomProxyIndex());
  }

  private void transitionToEngaged() {
    Log.i(TAG, "proxy engaged successfully");
    cancelAllTimers();
    state = State.ENGAGED;
    scheduleDirectRecheck();
  }

  private void onDirectRecheck() {
    if (state != State.ENGAGED) return;
    DcContext dcContext = DcHelper.getContext(context);
    String host = dcContext.getConfig("configured_mail_server");
    int port = dcContext.getConfigInt("configured_mail_port");
    if (host != null && !host.isEmpty() && port > 0 && directRelayReachable(host, port)) {
      Log.i(TAG, "direct relay reachable again - disengaging proxy");
      disengage();
      state = State.DIRECT;
      onConnectivityChanged();
    } else {
      scheduleDirectRecheck();
    }
  }

  // --- Proxy engage / disengage ---

  private void engageCurrentProxy() {
    triedCount++;
    String chosen = PROXIES[proxyIdx];
    Log.i(TAG, "engaging proxy #" + proxyIdx);
    DcContext dcContext = DcHelper.getContext(context);

    // Merge bundled proxies into proxy_url, keeping user entries, no duplicates, chosen first.
    LinkedHashSet<String> proxies = new LinkedHashSet<>();
    proxies.add(chosen);
    for (String p : PROXIES) {
      proxies.add(p);
    }
    for (String line : dcContext.getConfig(CONFIG_PROXY_URL).split("\n")) {
      line = line.trim();
      if (!line.isEmpty()) {
        proxies.add(line);
      }
    }

    dcContext.setConfig(CONFIG_PROXY_URL, String.join("\n", proxies));
    dcContext.setConfig(CONFIG_PROXY_ENABLED, "1");
    setAutoProxyEngaged(true);
    dcContext.restartIo();
  }

  private void disengage() {
    if (!autoProxyEngaged()) return; // never disable a proxy the user enabled
    cancelAllTimers();
    DcContext dcContext = DcHelper.getContext(context);
    dcContext.setConfig(CONFIG_PROXY_ENABLED, "0");
    dcContext.restartIo();
    setAutoProxyEngaged(false);
  }

  // --- Guard (ТЗ §6) ---

  /** Whether we're allowed to engage a proxy on the currently selected account. */
  private boolean mayEngage() {
    if (!hasProxies()) return false;
    if (!DcHelper.isConfigured(context)) return false;
    // If a proxy is already enabled but we didn't enable it, the user did - don't interfere.
    boolean proxyEnabled = DcHelper.getInt(context, CONFIG_PROXY_ENABLED) == 1;
    if (proxyEnabled && !autoProxyEngaged()) return false;
    return true;
  }

  // --- Network probes (ТЗ §5) ---

  /** GET the internet probe URL; any HTTP response (without exception) counts as success. */
  private boolean hasInternet() {
    HttpsURLConnection conn = null;
    try {
      conn = (HttpsURLConnection) new URL(INTERNET_PROBE_URL).openConnection();
      conn.setConnectTimeout(PROBE_TIMEOUT_MS);
      conn.setReadTimeout(PROBE_TIMEOUT_MS);
      conn.setUseCaches(false);
      conn.setRequestMethod("GET");
      conn.getResponseCode();
      return true;
    } catch (IOException e) {
      return false;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  /** Plain TCP connect, bypassing the core's HTTP proxy, to test direct relay reachability. */
  private boolean directRelayReachable(String host, int port) {
    Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
      return true;
    } catch (IOException e) {
      return false;
    } finally {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  // --- Helpers ---

  private boolean isRelayConnected() {
    return DcHelper.getContext(context).getConnectivity() >= DcContext.DC_CONNECTIVITY_CONNECTED;
  }

  /** Decode the XOR+base64 proxy blob from BuildConfig into memory. Never persists plaintext. */
  private static String[] decodeProxies() {
    String blob = BuildConfig.AUTO_PROXY_OBF;
    String keyB64 = BuildConfig.AUTO_PROXY_OBF_KEY;
    if (blob == null || blob.isEmpty() || keyB64 == null || keyB64.isEmpty()) {
      return new String[0];
    }
    try {
      byte[] key = Base64.decode(keyB64, Base64.DEFAULT);
      byte[] data = Base64.decode(blob, Base64.DEFAULT);
      return AutoProxyObfuscation.deobfuscate(data, key);
    } catch (Exception e) {
      Log.w(TAG, "failed to decode proxy list", e);
      return new String[0];
    }
  }

  private boolean hasProxies() {
    return PROXIES.length > 0;
  }

  private int randomProxyIndex() {
    return random.nextInt(PROXIES.length);
  }

  private void scheduleGrace() {
    graceScheduled = true;
    handler.postDelayed(graceTask, GRACE_MS);
  }

  private void cancelGrace() {
    graceScheduled = false;
    handler.removeCallbacks(graceTask);
  }

  private void scheduleDirectRecheck() {
    handler.removeCallbacks(directRecheckTask);
    handler.postDelayed(directRecheckTask, DIRECT_RECHECK_MS);
  }

  private void cancelAllTimers() {
    cancelGrace();
    handler.removeCallbacks(proxyTryTask);
    handler.removeCallbacks(directRecheckTask);
    handler.removeCallbacks(backoffTask);
  }

  private boolean autoProxyEngaged() {
    return Prefs.getBooleanPreference(context, PREF_AUTO_PROXY_ENGAGED, false);
  }

  private void setAutoProxyEngaged(boolean engaged) {
    Prefs.setBooleanPreference(context, PREF_AUTO_PROXY_ENGAGED, engaged);
  }
}
