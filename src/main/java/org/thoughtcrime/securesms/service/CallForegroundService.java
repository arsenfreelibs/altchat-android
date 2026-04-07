package org.thoughtcrime.securesms.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.notifications.NotificationCenter;
import org.thoughtcrime.securesms.util.Util;

/**
 * Foreground service (phoneCall) that keeps the process alive during an incoming call
 * so Android does not kill the app.
 *
 * Lifecycle:
 * - {@link org.thoughtcrime.securesms.notifications.NotificationCenter#notifyCall} starts it
 * - {@link org.thoughtcrime.securesms.notifications.NotificationCenter#removeCallNotification} stops it
 *
 * Two-phase notification:
 * 1. {@code onCreate()} posts a silent placeholder immediately to satisfy the 5-second FGS rule.
 * 2. {@code onStartCommand()} builds the full CallStyle notification on a background thread
 *    and replaces the placeholder via a second {@code startForeground()} call — same ID, one slot.
 */
public class CallForegroundService extends Service {
    private static final String TAG = CallForegroundService.class.getSimpleName();

    private static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final String EXTRA_CALL_ID    = "call_id";
    private static final String EXTRA_PAYLOAD    = "payload";

    public static void start(Context context, int accountId, int callId, String payload) {
        GenericForegroundService.createFgNotificationChannel(context);
        Intent intent = new Intent(context, CallForegroundService.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(EXTRA_CALL_ID, callId);
        intent.putExtra(EXTRA_PAYLOAD, payload);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            Log.w(TAG, "Failed to start CallForegroundService: " + e);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CallForegroundService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Must call startForeground within 5 seconds.
        // Use a silent placeholder to satisfy the FGS rule immediately; the real
        // CallStyle notification is posted in onStartCommand() on a background thread.
        Notification silent = new NotificationCompat.Builder(this, NotificationCenter.CH_GENERIC)
                .setContentTitle(getString(R.string.call_status_incoming))
                .setSmallIcon(R.drawable.icon_notification)
                .setSilent(true)
                .setOngoing(true)
                .build();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NotificationCenter.ID_CALL, silent,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
        } else {
            startForeground(NotificationCenter.ID_CALL, silent);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        int accountId  = intent.getIntExtra(EXTRA_ACCOUNT_ID, 0);
        int callId     = intent.getIntExtra(EXTRA_CALL_ID, 0);
        String payload = intent.getStringExtra(EXTRA_PAYLOAD);
        if (accountId == 0) return START_NOT_STICKY;

        // Build the full CallStyle notification on a background thread.
        // startForeground() called a second time replaces the silent placeholder
        // in the same slot — so only one notification is ever visible.
        Util.runOnAnyBackgroundThread(() -> {
            Notification notification = DcHelper.getNotificationCenter(this)
                    .buildCallNotification(accountId, callId, payload);
            if (notification != null) {
                try {
                    // stopForeground removes the silent placeholder; startForeground then
                    // re-promotes with a fresh notification — the OS treats this as a new post,
                    // firing the full-screen intent (wakes screen) and the ringtone (FLAG_INSISTENT).
                    stopForeground(true);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        startForeground(NotificationCenter.ID_CALL, notification,
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
                    } else {
                        startForeground(NotificationCenter.ID_CALL, notification);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to update call notification", e);
                }
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    /** Called by Android 14+ when the system decides the foreground service has timed out. */
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
