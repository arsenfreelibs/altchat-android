package org.thoughtcrime.securesms.proxy;

import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_PROXY_ENABLED;
import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_PROXY_URL;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import com.b44t.messenger.DcAccounts;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
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
 *
 * <p>The engage/disengage state machine acts on the currently selected account, but the
 * "is auto-proxy on" flag is tracked <b>per account</b> and the periodic direct-relay recheck
 * sweeps <b>all</b> engaged accounts, so a non-selected account can never get stuck on a proxy.
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

  // Legacy single global flag (pre per-account). Kept only to migrate it on first start.
  private static final String PREF_AUTO_PROXY_ENGAGED_LEGACY = "pref_auto_proxy_engaged";
  // Per-account flag is PREF_AUTO_PROXY_ENGAGED_PREFIX + accountId.
  private static final String PREF_AUTO_PROXY_ENGAGED_PREFIX = "pref_auto_proxy_engaged_";

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
  private boolean reengageGraceScheduled = false;
  private boolean backoffScheduled = false;

  // Timer runnables kept as fields so they can be cancelled.
  private final Runnable graceTask = this::onGraceExpired;
  private final Runnable reengageGraceTask = this::onReengageGraceExpired;
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
    recoverEngagedAccounts();

    int sel = selectedAccountId();
    if (autoProxyEngaged(sel) && hasProxies()) {
      // We engaged on the selected account in a previous session - resume in ENGAGED.
      Log.i(TAG, "resuming in ENGAGED state on account " + sel);
      state = State.ENGAGED;
    } else {
      if (autoProxyEngaged(sel)) {
        // Flag set but no bundled proxies available anymore - undo and stay direct.
        disengageAccount(selectedAccount(), sel);
      }
      state = State.DIRECT;
    }

    // Keep rechecking as long as ANY account is auto-engaged (incl. non-selected stuck ones),
    // so they get rolled back to direct as soon as their relay is reachable again.
    if (anyAccountEngaged()) {
      scheduleDirectRecheck();
    }
    if (state == State.DIRECT) {
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
        // Ignore connectivity while backing off (proxy is off; a recovering direct relay must not
        // be mistaken for "proxy engaged"). onBackoffExpired re-evaluates after the pause.
        if (!backoffScheduled && connected) {
          transitionToEngaged();
        }
        break;
      case ENGAGED:
        if (connected) {
          cancelReengageGrace();
        } else if (!reengageGraceScheduled) {
          // Debounce: only react if the relay stays unreachable past the grace window. A transient
          // dip (a normal reconnect / network handover) must not restart I/O mid-send. Note WORKING
          // already counts as connected, so an active send won't get here.
          scheduleReengageGrace();
        }
        break;
    }
  }

  private void onReengageGraceExpired() {
    reengageGraceScheduled = false;
    if (state != State.ENGAGED) return;
    if (isRelayConnected()) return; // recovered during grace
    // If the relay is reachable directly now, go direct instead of re-engaging the proxy.
    if (relayReachableDirect(selectedAccount())) {
      Log.i(TAG, "relay reachable directly - disengaging instead of re-engaging");
      disengageAccount(selectedAccount(), selectedAccountId());
      state = State.DIRECT;
      onConnectivityChanged();
      return;
    }
    Log.i(TAG, "proxy connection lost (after grace) - re-engaging");
    startEngaging(randomProxyIndex());
  }

  private void onGraceExpired() {
    graceScheduled = false;
    if (state != State.DIRECT) return;
    if (isRelayConnected()) return; // recovered during grace
    if (!mayEngage()) return;

    // A: don't engage if the relay is actually reachable directly. The core can report
    // NOT_CONNECTED for IMAP-level / backoff reasons while a raw TCP connect to the relay still
    // succeeds - in that case a proxy would not help and just adds churn.
    if (relayReachableDirect(selectedAccount())) {
      Log.i(TAG, "relay reachable directly - not engaging");
      return;
    }

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
    if (!tryEngageFromCurrent()) {
      revertAndBackoff();
    }
  }

  /**
   * B: apply the next proxy whose host is TCP-reachable, starting at {@link #proxyIdx}, and wait
   * for it to connect. Dead hosts are skipped without touching the account config. Returns false if
   * none of the bundled proxies has a reachable host.
   */
  private boolean tryEngageFromCurrent() {
    for (int i = 0; i < PROXIES.length; i++) {
      int idx = (proxyIdx + i) % PROXIES.length;
      if (proxyHostReachable(PROXIES[idx])) {
        proxyIdx = idx;
        triedCount++;
        applyProxy(selectedAccount(), selectedAccountId(), PROXIES[idx]);
        handler.postDelayed(proxyTryTask, PROXY_TRY_MS);
        return true;
      }
      Log.i(TAG, "proxy host #" + idx + " unreachable, skipping");
    }
    return false;
  }

  private void onProxyTryExpired() {
    if (state != State.ENGAGING) return;
    if (isRelayConnected()) {
      transitionToEngaged();
      return;
    }
    if (triedCount >= PROXIES.length) {
      revertAndBackoff();
      return;
    }
    proxyIdx = (proxyIdx + 1) % PROXIES.length;
    if (!tryEngageFromCurrent()) {
      revertAndBackoff();
    }
  }

  /** All candidates dead/failed: undo the proxy (don't sit on a dead one) and pause before retry. */
  private void revertAndBackoff() {
    Log.i(TAG, "no working proxy - reverting to direct, backing off " + (BACKOFF_MS / 1000) + "s");
    disengageAccount(selectedAccount(), selectedAccountId());
    backoffScheduled = true;
    handler.postDelayed(backoffTask, BACKOFF_MS);
  }

  private void onBackoffExpired() {
    backoffScheduled = false;
    if (state != State.ENGAGING) return;
    // If the relay recovered during backoff, just go direct instead of re-engaging.
    if (relayReachableDirect(selectedAccount())) {
      Log.i(TAG, "relay recovered during backoff - going direct");
      state = State.DIRECT;
      onConnectivityChanged();
      return;
    }
    startEngaging(randomProxyIndex());
  }

  private void transitionToEngaged() {
    Log.i(TAG, "proxy engaged successfully");
    cancelAllTimers();
    state = State.ENGAGED;
    scheduleDirectRecheck();
  }

  /** C: sweep every auto-engaged account and roll back any whose relay is directly reachable. */
  private void onDirectRecheck() {
    boolean selectedDisengaged = false;
    boolean anyStillEngaged = false;
    DcAccounts accounts = accounts();
    for (int id : accounts.getAll()) {
      if (!autoProxyEngaged(id)) continue;
      DcContext acc = accounts.getAccount(id);
      if (relayReachableDirect(acc)) {
        Log.i(TAG, "direct relay reachable on account " + id + " - disengaging proxy");
        disengageAccount(acc, id);
        if (id == selectedAccountId()) {
          selectedDisengaged = true;
        }
      } else {
        anyStillEngaged = true;
      }
    }
    if (selectedDisengaged && state == State.ENGAGED) {
      state = State.DIRECT;
    }
    if (anyStillEngaged) {
      scheduleDirectRecheck();
    }
    if (state == State.DIRECT) {
      onConnectivityChanged();
    }
  }

  // --- Proxy engage / disengage ---

  /** Set the chosen proxy first in proxy_url (keeping user/bundled entries), enable it, restart IO. */
  private void applyProxy(DcContext acc, int accountId, String chosen) {
    Log.i(TAG, "engaging proxy #" + proxyIdx + " on account " + accountId);
    LinkedHashSet<String> proxies = new LinkedHashSet<>();
    proxies.add(chosen);
    for (String p : PROXIES) {
      proxies.add(p);
    }
    for (String line : acc.getConfig(CONFIG_PROXY_URL).split("\n")) {
      line = line.trim();
      if (!line.isEmpty()) {
        proxies.add(line);
      }
    }
    acc.setConfig(CONFIG_PROXY_URL, String.join("\n", proxies));
    acc.setConfig(CONFIG_PROXY_ENABLED, "1");
    setAutoProxyEngaged(accountId, true);
    acc.restartIo();
  }

  private void disengageAccount(DcContext acc, int accountId) {
    if (!autoProxyEngaged(accountId)) return; // never disable a proxy the user enabled
    acc.setConfig(CONFIG_PROXY_ENABLED, "0");
    acc.restartIo();
    setAutoProxyEngaged(accountId, false);
  }

  // --- Recovery / migration (C) ---

  /**
   * On start, reconcile the per-account flags with reality: migrate the legacy global flag, and
   * mark any account that is currently sitting on one of our bundled proxies as auto-engaged, so
   * the recheck sweep can roll it back. This is what self-heals an account stuck from before.
   */
  private void recoverEngagedAccounts() {
    if (Prefs.getBooleanPreference(context, PREF_AUTO_PROXY_ENGAGED_LEGACY, false)) {
      setAutoProxyEngaged(selectedAccountId(), true);
      Prefs.setBooleanPreference(context, PREF_AUTO_PROXY_ENGAGED_LEGACY, false);
    }
    if (!hasProxies()) return;
    DcAccounts accounts = accounts();
    for (int id : accounts.getAll()) {
      if (autoProxyEngaged(id)) continue;
      DcContext acc = accounts.getAccount(id);
      if (acc.getConfigInt(CONFIG_PROXY_ENABLED) == 1 && firstProxyIsBundled(acc)) {
        Log.i(TAG, "recovered auto-engaged proxy on account " + id);
        setAutoProxyEngaged(id, true);
      }
    }
  }

  private boolean firstProxyIsBundled(DcContext acc) {
    String url = acc.getConfig(CONFIG_PROXY_URL);
    if (url == null || url.isEmpty()) return false;
    String first = url.split("\n", 2)[0].trim();
    for (String p : PROXIES) {
      if (p.equals(first)) return true;
    }
    return false;
  }

  private boolean anyAccountEngaged() {
    for (int id : accounts().getAll()) {
      if (autoProxyEngaged(id)) return true;
    }
    return false;
  }

  // --- Guard (ТЗ §6) ---

  /** Whether we're allowed to engage a proxy on the currently selected account. */
  private boolean mayEngage() {
    if (!hasProxies()) return false;
    if (!DcHelper.isConfigured(context)) return false;
    // If a proxy is already enabled but we didn't enable it, the user did - don't interfere.
    boolean proxyEnabled = DcHelper.getInt(context, CONFIG_PROXY_ENABLED) == 1;
    if (proxyEnabled && !autoProxyEngaged(selectedAccountId())) return false;
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

  /** True if a plain TCP connect to the account's relay succeeds (bypassing the core's proxy). */
  private boolean relayReachableDirect(DcContext acc) {
    String host = acc.getConfig("configured_mail_server");
    int port = acc.getConfigInt("configured_mail_port");
    return host != null && !host.isEmpty() && port > 0 && tcpReachable(host, port);
  }

  /** B: true if the proxy's own host:port accepts a TCP connection (cheap liveness pre-check). */
  private boolean proxyHostReachable(String proxyUrl) {
    try {
      URI uri = URI.create(proxyUrl);
      String host = uri.getHost();
      int port = uri.getPort();
      if (host == null || port <= 0) return false;
      return tcpReachable(host, port);
    } catch (Exception e) {
      return false;
    }
  }

  /** Plain TCP connect with timeout, closed in finally. Bypasses the core's HTTP proxy. */
  private boolean tcpReachable(String host, int port) {
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
    // WORKING means the relay is reachable and actively transferring; only CONNECTING /
    // NOT_CONNECTED count as "down". Using CONNECTED (idle) here would misread every normal
    // send/sync (which sits at WORKING) as a dropped connection and flap the proxy mid-send.
    return DcHelper.getContext(context).getConnectivity() >= DcContext.DC_CONNECTIVITY_WORKING;
  }

  private DcContext selectedAccount() {
    return DcHelper.getContext(context);
  }

  private int selectedAccountId() {
    return selectedAccount().getAccountId();
  }

  private DcAccounts accounts() {
    return DcHelper.getAccounts(context);
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

  private void scheduleReengageGrace() {
    reengageGraceScheduled = true;
    handler.postDelayed(reengageGraceTask, GRACE_MS);
  }

  private void cancelReengageGrace() {
    reengageGraceScheduled = false;
    handler.removeCallbacks(reengageGraceTask);
  }

  private void scheduleDirectRecheck() {
    handler.removeCallbacks(directRecheckTask);
    handler.postDelayed(directRecheckTask, DIRECT_RECHECK_MS);
  }

  private void cancelAllTimers() {
    cancelGrace();
    cancelReengageGrace();
    backoffScheduled = false;
    handler.removeCallbacks(proxyTryTask);
    handler.removeCallbacks(directRecheckTask);
    handler.removeCallbacks(backoffTask);
  }

  private boolean autoProxyEngaged(int accountId) {
    return Prefs.getBooleanPreference(context, PREF_AUTO_PROXY_ENGAGED_PREFIX + accountId, false);
  }

  private void setAutoProxyEngaged(int accountId, boolean engaged) {
    Prefs.setBooleanPreference(context, PREF_AUTO_PROXY_ENGAGED_PREFIX + accountId, engaged);
  }
}
