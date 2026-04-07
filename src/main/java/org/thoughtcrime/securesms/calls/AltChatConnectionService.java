package org.thoughtcrime.securesms.calls;

import android.os.Build;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import androidx.annotation.RequiresApi;

/**
 * Self-managed {@link ConnectionService} that registers alt.chat VoIP calls with the
 * Android Telecom stack. Being registered gives the OS visibility into active calls,
 * which is required for the {@code USE_FULL_SCREEN_INTENT} privilege on Android 14+.
 *
 * <p>The service itself is thin: all call logic stays in the existing alt.chat code.
 * Telecom is informed of state changes via {@link AltChatConnection} instances stored
 * in the static {@link AltChatConnection#get(int)} registry.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class AltChatConnectionService extends ConnectionService {

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle handle, ConnectionRequest request) {
        int callId = request.getExtras().getInt(TelecomHelper.EXTRA_CALL_ID, 0);
        String callerName = request.getExtras().getString(TelecomHelper.EXTRA_CALLER_NAME, "");

        AltChatConnection connection = new AltChatConnection(callId);
        connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED);
        connection.setAddress(request.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
        connection.setRinging();
        return connection;
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle handle, ConnectionRequest request) {
        // Nothing to do: the existing call-notification flow already handles the incoming call.
    }
}
