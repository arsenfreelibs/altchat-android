package org.thoughtcrime.securesms.calls;

import android.os.Build;
import android.telecom.Connection;
import android.telecom.DisconnectCause;

import androidx.annotation.RequiresApi;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a single alt.chat call registered with the Android Telecom stack.
 * Instances are stored in a static map keyed by DC callId so that event handlers
 * can transition state (RINGING → ACTIVE → DISCONNECTED) without holding a reference
 * to the service that created them.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class AltChatConnection extends Connection {

    private static final ConcurrentHashMap<Integer, AltChatConnection> sActive = new ConcurrentHashMap<>();

    private final int callId;

    AltChatConnection(int callId) {
        this.callId = callId;
        setConnectionProperties(PROPERTY_SELF_MANAGED);
        if (callId != 0) {
            sActive.put(callId, this);
        }
    }

    /** Retrieve the connection for a given DC callId, or {@code null} if not found. */
    public static AltChatConnection get(int callId) {
        return sActive.get(callId);
    }

    // ---- Telecom lifecycle callbacks -------------------------------------------------------

    @Override
    public void onDisconnect() {
        setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        destroy();
    }

    @Override
    public void onAbort() {
        setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
        destroy();
    }

    @Override
    public void onReject() {
        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
        destroy();
    }

    @Override
    public void onStateChanged(int state) {
        if (state == STATE_DISCONNECTED) {
            sActive.remove(callId);
        }
    }
}
