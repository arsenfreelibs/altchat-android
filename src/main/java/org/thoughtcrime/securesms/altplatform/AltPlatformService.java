package org.thoughtcrime.securesms.altplatform;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import chat.delta.rpc.Rpc;
import chat.delta.rpc.types.EnteredLoginParam;

import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.altplatform.crypto.AltCryptoException;
import org.thoughtcrime.securesms.altplatform.crypto.AltKeyCrypto;
import org.thoughtcrime.securesms.altplatform.network.AltApiService;
import org.thoughtcrime.securesms.altplatform.network.dto.AltApiResponse;
import org.thoughtcrime.securesms.altplatform.network.dto.PrivateKeyResponse;
import org.thoughtcrime.securesms.altplatform.network.dto.RegisterRequest;
import org.thoughtcrime.securesms.altplatform.network.dto.ResendCodeRequest;
import org.thoughtcrime.securesms.altplatform.network.dto.RestoreRequest;
import org.thoughtcrime.securesms.altplatform.network.dto.UserProfileResponse;
import org.thoughtcrime.securesms.altplatform.network.dto.VerifyRequest;
import org.thoughtcrime.securesms.altplatform.network.dto.VerifyResponse;
import org.thoughtcrime.securesms.altplatform.storage.AltPrefs;
import org.thoughtcrime.securesms.altplatform.storage.AltTokenStorage;
import org.thoughtcrime.securesms.connect.DcHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Central service class orchestrating Alt Platform API calls with DC core operations.
 * Not an Android Service — plain Java class, instantiated with ApplicationContext.
 * All methods are synchronous; call from a background thread.
 */
public class AltPlatformService {

    private static final String TAG = AltPlatformService.class.getSimpleName();
    private static final long IMEX_TIMEOUT_SEC = 60;

    public enum RegisterResult {
        SUCCESS, USERNAME_TAKEN, EMAIL_TAKEN, USERNAME_RESERVED, INVALID_USERNAME, NETWORK_ERROR
    }

    public enum VerifyResult {
        SUCCESS, INVALID_CODE, NETWORK_ERROR
    }

    public enum ResendResult {
        SUCCESS, RATE_LIMITED, USER_NOT_FOUND, ALREADY_ACTIVE, NETWORK_ERROR
    }

    public enum RestoreInitResult {
        SUCCESS, USER_NOT_FOUND, NETWORK_ERROR
    }

    public enum RestoreKeyResult {
        SUCCESS, WRONG_PASSWORD, INVALID_CODE, NETWORK_ERROR, IMPORT_FAILED
    }

    private final Context context;
    private final AltApiService api;

    public AltPlatformService(Context context) {
        this.context = context.getApplicationContext();
        this.api = new AltApiService(context);
    }

    /**
     * Registers a user on Alt Platform.
     * Collects all addrs from rpc.listTransports(), exports DC keys, encrypts private key,
     * and calls /v1/users/register.
     */
    public RegisterResult register(String username, String email, String displayName,
                                   String recoveryPassword) {
        Rpc rpc = DcHelper.getRpc(context);
        int accountId = DcHelper.getAccounts(context).getSelectedAccount().getAccountId();

        // 1. Collect all addrs from all transports
        List<String> addrs = collectAddrs(rpc, accountId);
        Log.d(TAG, "register() addrs=" + addrs);
        if (addrs.isEmpty()) {
            Log.e(TAG, "No transports configured");
            return RegisterResult.NETWORK_ERROR;
        }

        // 2. Get keys from DC core
        String publicKey;
        String privateKeyArmored;
        try {
            Log.d(TAG, "register() calling getSelfPublicKeyArmored");
            publicKey = rpc.getSelfPublicKeyArmored(accountId);
            Log.d(TAG, "register() publicKey len=" + (publicKey != null ? publicKey.length() : -1));
            Log.d(TAG, "register() calling getSelfPrivateKeyArmored");
            privateKeyArmored = rpc.getSelfPrivateKeyArmored(accountId);
            Log.d(TAG, "register() privateKey len=" + (privateKeyArmored != null ? privateKeyArmored.length() : -1));
        } catch (Exception e) {
            Log.e(TAG, "Failed to get keys from DC core", e);
            return RegisterResult.NETWORK_ERROR;
        }
        // 3. Get fingerprint directly from DC core
        String fingerprint;
        try {
            fingerprint = rpc.getSelfFingerprintHex(accountId);
            Log.d(TAG, "register() fingerprint len=" + (fingerprint != null ? fingerprint.length() : -1));
        } catch (Exception e) {
            Log.e(TAG, "Failed to get fingerprint from DC core", e);
            return RegisterResult.NETWORK_ERROR;
        }

        // 4. Encrypt private key
        byte[] encrypted;
        try {
            encrypted = AltKeyCrypto.encrypt(privateKeyArmored.getBytes("UTF-8"), recoveryPassword);
        } catch (AltCryptoException e) {
            Log.e(TAG, "Encryption failed", e);
            return RegisterResult.NETWORK_ERROR;
        } catch (java.io.UnsupportedEncodingException e) {
            return RegisterResult.NETWORK_ERROR;
        }
        String encryptedPrivateKey = Base64.encodeToString(encrypted, Base64.NO_WRAP);

        // 5. Call API
        RegisterRequest req = new RegisterRequest(username, email, addrs, displayName,
                publicKey, fingerprint, encryptedPrivateKey);
        Log.d(TAG, "register() calling API addrs=" + addrs + " fingerprint=" + fingerprint);
        Log.d(TAG, "register() publicKey(first80)=" + publicKey.substring(0, Math.min(80, publicKey.length())));
        Log.d(TAG, "register() encryptedPrivateKey len=" + encryptedPrivateKey.length());
        Log.d(TAG, "register() username=" + username + " email=" + email + " displayName=" + displayName);
        AltApiResponse<Void> resp = api.register(req);
        Log.d(TAG, "register() API response httpCode=" + resp.httpCode + " errorCode=" + resp.errorCode);
        return mapRegisterResult(resp);
    }

    public VerifyResult verifyEmail(String email, String code) {
        AltApiResponse<VerifyResponse> resp = api.verify(new VerifyRequest(email, code));
        if (resp.isNetworkError()) return VerifyResult.NETWORK_ERROR;
        if (resp.isSuccess() && resp.data != null && resp.data.token != null) {
            AltTokenStorage.saveToken(context, resp.data.token);
            return VerifyResult.SUCCESS;
        }
        if (resp.httpCode == 400) return VerifyResult.INVALID_CODE;
        return VerifyResult.NETWORK_ERROR;
    }

    public ResendResult resendCode(String email) {
        AltApiResponse<Void> resp = api.resendCode(new ResendCodeRequest(email));
        if (resp.isNetworkError()) return ResendResult.NETWORK_ERROR;
        if (resp.isSuccess()) return ResendResult.SUCCESS;
        if (resp.httpCode == 429) return ResendResult.RATE_LIMITED;
        if (resp.httpCode == 404) return ResendResult.USER_NOT_FOUND;
        if ("already_active".equals(resp.errorCode)) return ResendResult.ALREADY_ACTIVE;
        return ResendResult.NETWORK_ERROR;
    }

    public RestoreInitResult initiateRestore(String username, String email) {
        AltApiResponse<Void> resp = api.restore(new RestoreRequest(username, email));
        if (resp.isNetworkError()) return RestoreInitResult.NETWORK_ERROR;
        if (resp.isSuccess()) return RestoreInitResult.SUCCESS;
        if (resp.httpCode == 404) return RestoreInitResult.USER_NOT_FOUND;
        return RestoreInitResult.NETWORK_ERROR;
    }

    public RestoreKeyResult restoreKey(String email, String code, String recoveryPassword) {
        // 1. Verify code → get JWT
        AltApiResponse<VerifyResponse> verifyResp = api.verify(new VerifyRequest(email, code));
        if (verifyResp.isNetworkError()) return RestoreKeyResult.NETWORK_ERROR;
        if (!verifyResp.isSuccess()) return RestoreKeyResult.INVALID_CODE;
        if (verifyResp.data != null && verifyResp.data.token != null) {
            AltTokenStorage.saveToken(context, verifyResp.data.token);
        }

        // 2. Download encrypted private key
        AltApiResponse<PrivateKeyResponse> keyResp = api.getPrivateKey();
        if (keyResp.isNetworkError() || !keyResp.isSuccess() || keyResp.data == null) {
            return RestoreKeyResult.NETWORK_ERROR;
        }
        byte[] encryptedBlob = Base64.decode(keyResp.data.encryptedPrivateKey, Base64.NO_WRAP);

        // 3. Decrypt
        byte[] privKeyBytes;
        try {
            privKeyBytes = AltKeyCrypto.decrypt(encryptedBlob, recoveryPassword);
        } catch (AltCryptoException e) {
            return RestoreKeyResult.WRONG_PASSWORD;
        }

        // 4. Import key into DC core
        try {
            Rpc rpc = DcHelper.getRpc(context);
            int accountId = DcHelper.getAccounts(context).getSelectedAccount().getAccountId();
            rpc.importSelfKeyFromBytes(accountId, context.getCacheDir(), privKeyBytes);
            return RestoreKeyResult.SUCCESS;
        } catch (Exception e) {
            Log.e(TAG, "importSelfKeyFromBytes failed", e);
            return RestoreKeyResult.IMPORT_FAILED;
        }
    }

    public List<UserProfileResponse> searchUsers(String query) {
        AltApiResponse<List<UserProfileResponse>> resp = api.search(query);
        if (resp.isSuccess() && resp.data != null) return resp.data;
        return Collections.emptyList();
    }

    public UserProfileResponse getProfile(String username) {
        AltApiResponse<UserProfileResponse> resp = api.getProfile(username);
        return (resp.isSuccess()) ? resp.data : null;
    }

    /**
     * Adds a DC contact from an Alt Platform profile.
     * Creates via createContact (reliable), then imports PGP key via gossip vCard if available.
     *
     * @return contactId of the created/updated contact, or -1 on failure
     */
    public int addContactFromAlt(UserProfileResponse profile) {
        String primaryAddr = profile.primaryAddr();
        if (primaryAddr == null || primaryAddr.isEmpty()) {
            Log.e(TAG, "addContactFromAlt: no addr in profile");
            return -1;
        }
        String fn = (profile.name != null && !profile.name.isEmpty()) ? profile.name : primaryAddr;

        try {
            Rpc rpc = DcHelper.getRpc(context);
            int accountId = DcHelper.getAccounts(context).getSelectedAccount().getAccountId();

            // If we have a public key — import via vCard so DC creates a "key contact"
            // (contact linked by fingerprint in contacts.fingerprint + key in public_keys).
            // This is CRITICAL: DC only encrypts when the contact has a fingerprint-linked key.
            // Do NOT call createContact() first — it creates a contact with empty fingerprint,
            // and importVcardContents() would then create a DIFFERENT contact because
            // DC's add_or_lookup_ex() searches contacts by fingerprint, not by addr.
            if (profile.public_key != null && !profile.public_key.isEmpty()) {
                try {
                    String keyBase64 = profile.public_key
                            .replaceAll("-----[^\r\n]*-----", "")
                            .replaceAll("(?m)^=[A-Za-z0-9+/]{4}\\s*$", "")
                            .replaceAll("\\s+", "");
                    String vcard = "BEGIN:VCARD\r\n"
                            + "VERSION:4.0\r\n"
                            + "FN:" + fn + "\r\n"
                            + "EMAIL:" + primaryAddr + "\r\n"
                            + "KEY:data:application/pgp-keys;base64," + keyBase64 + "\r\n"
                            + "END:VCARD\r\n";
                    java.util.List<Integer> ids = rpc.importVcardContents(accountId, vcard);
                    if (ids != null && !ids.isEmpty() && ids.get(0) > 0) {
                        int contactId = ids.get(0);
                        Log.d(TAG, "addContactFromAlt: key contact created id=" + contactId);
                        return contactId;
                    }
                    Log.w(TAG, "addContactFromAlt: importVcardContents returned empty ids, falling back");
                } catch (Exception e) {
                    Log.w(TAG, "addContactFromAlt: importVcardContents failed: " + e.getMessage() + ", falling back");
                }
            }

            // Fallback: no key, just create a regular contact
            com.b44t.messenger.DcContext dcContext = DcHelper.getContext(context);
            int contactId = dcContext.createContact(fn, primaryAddr);
            if (contactId <= 0) {
                Log.e(TAG, "addContactFromAlt: createContact returned " + contactId);
                return -1;
            }
            Log.d(TAG, "addContactFromAlt: plain contact created id=" + contactId);
            return contactId;
        } catch (Exception e) {
            Log.e(TAG, "addContactFromAlt failed", e);
            return -1;
        }
    }

    // --- private helpers ---

    private List<String> collectAddrs(Rpc rpc, int accountId) {
        List<String> addrs = new ArrayList<>();
        try {
            List<EnteredLoginParam> transports = rpc.listTransports(accountId);
            for (EnteredLoginParam t : transports) {
                if (t.addr != null && !t.addr.isEmpty()) {
                    addrs.add(t.addr);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "listTransports failed", e);
        }
        return addrs;
    }

    private RegisterResult mapRegisterResult(AltApiResponse<Void> resp) {
        if (resp.isNetworkError()) return RegisterResult.NETWORK_ERROR;
        if (resp.isSuccess()) return RegisterResult.SUCCESS;
        switch (resp.httpCode) {
            case 409:
                if ("username_taken".equals(resp.errorCode)) return RegisterResult.USERNAME_TAKEN;
                if ("email_taken".equals(resp.errorCode)) return RegisterResult.EMAIL_TAKEN;
                return RegisterResult.USERNAME_TAKEN;
            case 422:
                if ("username_reserved".equals(resp.errorCode)) return RegisterResult.USERNAME_RESERVED;
                return RegisterResult.INVALID_USERNAME;
            default:
                return RegisterResult.NETWORK_ERROR;
        }
    }
}
