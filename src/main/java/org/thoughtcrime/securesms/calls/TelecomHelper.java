package org.thoughtcrime.securesms.calls;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;

import chat.delta.rpc.RpcException;
import chat.delta.rpc.types.CallInfo;

/**
 * Bridges alt.chat call events to the Android Telecom stack.
 * Registers a self-managed {@link PhoneAccount} so that the OS is aware of VoIP
 * calls (required for full-screen intent privileges on locked screens).
 *
 * <p>Requires {@link android.Manifest.permission#MANAGE_OWN_CALLS} (normal permission,
 * granted automatically when declared in the manifest) and API level ≥ 26.
 * All public methods are safe to call on any thread and on all API levels; calls on
 * API < 26 are silently ignored.
 */
public class TelecomHelper {

    private static final String TAG = TelecomHelper.class.getSimpleName();

    /** Bundle key: DC call/message id (int). */
    public static final String EXTRA_CALL_ID = "altchat_call_id";
    /** Bundle key: display name of the remote party (String). */
    public static final String EXTRA_CALLER_NAME = "altchat_caller_name";

    private static volatile TelecomHelper sInstance;

    private final Context context;

    private TelecomHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public static TelecomHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (TelecomHelper.class) {
                if (sInstance == null) {
                    sInstance = new TelecomHelper(context);
                }
            }
        }
        return sInstance;
    }

    // ---- PhoneAccount registration ---------------------------------------------------------

    /** Register (or refresh) the self-managed {@link PhoneAccount} with Telecom. */
    public void registerPhoneAccount() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            TelecomManager tm = telecomManager();
            if (tm == null) return;
            PhoneAccount account = PhoneAccount
                    .builder(phoneAccountHandle(), context.getString(R.string.app_name))
                    .setCapabilities(
                            PhoneAccount.CAPABILITY_SELF_MANAGED |
                            PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING)
                    .build();
            tm.registerPhoneAccount(account);
        } catch (Exception e) {
            Log.e(TAG, "registerPhoneAccount failed", e);
        }
    }

    // ---- Incoming calls --------------------------------------------------------------------

    /**
     * Called when {@code DC_EVENT_INCOMING_CALL} fires. Informs Telecom of the new
     * incoming call so the OS has visibility into the active VoIP session (required
     * for full-screen intent privileges). Telecom calls back into
     * {@link AltChatConnectionService} to create the {@link AltChatConnection}.
     */
    public void onIncomingCall(int accId, int callId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Util.runOnAnyBackgroundThread(() -> {
            try {
                TelecomManager tm = telecomManager();
                if (tm == null) return;

                DcContext dcContext = DcHelper.getAccounts(context).getAccount(accId);
                DcMsg msg = dcContext.getMsg(callId);
                DcContact contact = dcContext.getContact(msg.getFromId());
                String callerName = contact.getDisplayName();
                String callerAddr = contact.getAddr();

                boolean hasVideo = false;
                try {
                    CallInfo info = DcHelper.getRpc(context).callInfo(accId, callId);
                    hasVideo = Boolean.TRUE.equals(info.hasVideo);
                } catch (RpcException e) {
                    Log.w(TAG, "callInfo failed for incoming call", e);
                }

                Bundle extras = new Bundle();
                extras.putInt(EXTRA_CALL_ID, callId);
                extras.putString(EXTRA_CALLER_NAME, callerName);
                if (hasVideo) {
                    extras.putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE,
                            android.telecom.VideoProfile.STATE_BIDIRECTIONAL);
                }
                // Pass the address so Telecom can identify the call; mailto: is the natural scheme for email-based VoIP.
                extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                        Uri.fromParts("mailto", callerAddr, null));

                tm.addNewIncomingCall(phoneAccountHandle(), extras);
            } catch (Exception e) {
                Log.e(TAG, "onIncomingCall failed", e);
            }
        });
    }

    // ---- State transitions -----------------------------------------------------------------

    /** Called when {@code DC_EVENT_INCOMING_CALL_ACCEPTED} fires (other side answered). */
    public void onCallAccepted(int accId, int callId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        AltChatConnection conn = AltChatConnection.get(callId);
        if (conn != null) conn.setActive();
    }

    /** Called when {@code DC_EVENT_CALL_ENDED} fires. Disconnects the Telecom connection. */
    public void onCallEnded(int accId, int callId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        AltChatConnection conn = AltChatConnection.get(callId);
        if (conn != null) {
            conn.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            conn.destroy();
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    @RequiresApi(api = Build.VERSION_CODES.O)
    private PhoneAccountHandle phoneAccountHandle() {
        return new PhoneAccountHandle(
                new ComponentName(context, AltChatConnectionService.class),
                "altchat");
    }

    private TelecomManager telecomManager() {
        return (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
    }
}
