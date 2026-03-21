package org.thoughtcrime.securesms.altplatform;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import chat.delta.rpc.Rpc;
import chat.delta.rpc.types.EnteredLoginParam;

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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
        if (addrs.isEmpty()) {
            Log.e(TAG, "No transports configured");
            return RegisterResult.NETWORK_ERROR;
        }

        // 2. Export keys to temp dir
        File tempDir = createTempDir();
        if (tempDir == null) return RegisterResult.NETWORK_ERROR;

        try {
            try {
                rpc.exportSelfKeys(accountId, tempDir.getAbsolutePath(), "");
            } catch (Exception e) {
                Log.e(TAG, "exportSelfKeys failed", e);
                return RegisterResult.NETWORK_ERROR;
            }

            // 3. Read public key
            File pubKeyFile = findKeyFile(tempDir, "public");
            if (pubKeyFile == null) return RegisterResult.NETWORK_ERROR;
            byte[] pubKeyBytes = readFile(pubKeyFile);
            String publicKey = Base64.encodeToString(pubKeyBytes, Base64.NO_WRAP);

            // 4. Extract fingerprint from getEncryptionInfo
            String fingerprint = extractFingerprint(rpc, accountId);

            // 5. Read and encrypt private key
            File privKeyFile = findKeyFile(tempDir, "secret");
            if (privKeyFile == null) return RegisterResult.NETWORK_ERROR;
            byte[] privKeyBytes = readFile(privKeyFile);
            byte[] encrypted;
            try {
                encrypted = AltKeyCrypto.encrypt(privKeyBytes, recoveryPassword);
            } catch (AltCryptoException e) {
                Log.e(TAG, "Encryption failed", e);
                return RegisterResult.NETWORK_ERROR;
            }
            String encryptedPrivateKey = Base64.encodeToString(encrypted, Base64.NO_WRAP);

            // 6. Call API
            RegisterRequest req = new RegisterRequest(username, email, addrs, displayName,
                    publicKey, fingerprint, encryptedPrivateKey);
            AltApiResponse<Void> resp = api.register(req);
            return mapRegisterResult(resp);

        } finally {
            deleteDir(tempDir);
        }
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

        // 4. Write to temp dir and import
        File tempDir = createTempDir();
        if (tempDir == null) return RestoreKeyResult.NETWORK_ERROR;
        try {
            File privKeyFile = new File(tempDir, "secret-key-default.asc");
            try (FileOutputStream fos = new FileOutputStream(privKeyFile)) {
                fos.write(privKeyBytes);
            }

            // 5. Import via imex (async) — wait for DC_EVENT_IMEX_PROGRESS
            boolean success = importKeysSync(tempDir.getAbsolutePath());
            return success ? RestoreKeyResult.SUCCESS : RestoreKeyResult.IMPORT_FAILED;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write temp key file", e);
            return RestoreKeyResult.NETWORK_ERROR;
        } finally {
            deleteDir(tempDir);
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
     * Uses the primary addr (addrs[0]); DC core stores one addr per contact.
     * TODO: When DC core supports multiple addrs per contact, loop over profile.addrs.
     *
     * @return contactId of the created/updated contact
     */
    public int addContactFromAlt(UserProfileResponse profile) {
        Rpc rpc = DcHelper.getRpc(context);
        int accountId = DcHelper.getAccounts(context).getSelectedAccount().getAccountId();

        String primaryAddr = profile.primaryAddr();
        String fn = (profile.name != null && !profile.name.isEmpty()) ? profile.name : primaryAddr;

        String vcard = "BEGIN:VCARD\r\n"
                + "VERSION:4.0\r\n"
                + "FN:" + fn + "\r\n"
                + "EMAIL:" + primaryAddr + "\r\n"
                + "KEY;MEDIATYPE=application/pgp-keys:" + profile.publicKey + "\r\n"
                + "END:VCARD";

        try {
            List<Integer> ids = rpc.importVcardContents(accountId, vcard);
            return (ids != null && !ids.isEmpty()) ? ids.get(0) : -1;
        } catch (Exception e) {
            Log.e(TAG, "importVcardContents failed", e);
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

    private String extractFingerprint(Rpc rpc, int accountId) {
        try {
            // getContactEncryptionInfo for DC_CONTACT_ID_SELF (1) returns our own fingerprint info
            String info = rpc.getContactEncryptionInfo(accountId, 1);
            if (info != null) {
                for (String line : info.split("\\n")) {
                    if (line.toLowerCase().contains("fingerprint")) {
                        String[] parts = line.split(":", 2);
                        if (parts.length >= 2) return parts[1].trim().replace(" ", "");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getContactEncryptionInfo failed", e);
        }
        return "";
    }

    private File findKeyFile(File dir, String type) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().contains(type)) return f;
        }
        return null;
    }

    private byte[] readFile(File file) throws RuntimeException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            if (read != data.length) throw new IOException("Short read");
            return data;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file", e);
        }
    }

    private File createTempDir() {
        File dir = new File(context.getCacheDir(), "altkeys_" + System.currentTimeMillis());
        if (!dir.mkdirs()) {
            Log.e(TAG, "Failed to create temp dir");
            return null;
        }
        return dir;
    }

    private boolean importKeysSync(String dirPath) {
        try {
            Rpc rpc = DcHelper.getRpc(context);
            int accountId = DcHelper.getAccounts(context).getSelectedAccount().getAccountId();
            rpc.importSelfKeys(accountId, dirPath, "");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "importSelfKeys failed", e);
            return false;
        }
    }

    private void deleteDir(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.delete();
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
