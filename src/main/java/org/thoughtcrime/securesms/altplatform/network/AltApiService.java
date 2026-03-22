package org.thoughtcrime.securesms.altplatform.network;

import android.content.Context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.altplatform.network.dto.AltApiResponse;
import org.thoughtcrime.securesms.altplatform.network.dto.PrivateKeyResponse;
import org.thoughtcrime.securesms.altplatform.network.dto.RegisterRequest;
import org.thoughtcrime.securesms.altplatform.network.dto.ResendCodeRequest;
import org.thoughtcrime.securesms.altplatform.network.dto.RestoreRequest;
import org.thoughtcrime.securesms.altplatform.network.dto.UserProfileResponse;
import org.thoughtcrime.securesms.altplatform.network.dto.VerifyRequest;
import org.thoughtcrime.securesms.altplatform.network.dto.VerifyResponse;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

/**
 * Typed HTTP service for Alt Platform API.
 * All methods are synchronous — call from background thread.
 */
public class AltApiService {

    private static final String TAG = AltApiService.class.getSimpleName();

    private final AltApiClient client;
    private final ObjectMapper mapper;

    public AltApiService(Context context) {
        this.client = new AltApiClient(context, BuildConfig.ALT_API_BASE_URL);
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public AltApiResponse<Void> register(RegisterRequest req) {
        return postVoid("/v1/users/register", req);
    }

    public AltApiResponse<VerifyResponse> verify(VerifyRequest req) {
        return post("/v1/users/verify", req, VerifyResponse.class);
    }

    public AltApiResponse<Void> resendCode(ResendCodeRequest req) {
        return postVoid("/v1/users/resend-code", req);
    }

    public AltApiResponse<Void> restore(RestoreRequest req) {
        return postVoid("/v1/users/restore", req);
    }

    public AltApiResponse<PrivateKeyResponse> getPrivateKey() {
        return get("/v1/users/me/private-key", PrivateKeyResponse.class);
    }

    public AltApiResponse<List<UserProfileResponse>> search(String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            return getList("/v1/users/search?q=" + encoded, new TypeReference<List<UserProfileResponse>>() {});
        } catch (UnsupportedEncodingException e) {
            return AltApiResponse.networkError();
        }
    }

    public AltApiResponse<UserProfileResponse> getProfile(String username) {
        return get("/v1/users/" + username, UserProfileResponse.class);
    }

    // --- helpers ---

    private <T> AltApiResponse<T> post(String path, Object body, Class<T> clazz) {
        try {
            String json = mapper.writeValueAsString(body);
            android.util.Log.d(TAG, "post " + path + " body=" + json);
            AltApiClient.Response resp = client.post(path, json);
            if (resp.isNetworkError()) return AltApiResponse.networkError();
            android.util.Log.d(TAG, "post " + path + " resp=" + resp.code + " body=" + resp.body);
            if (resp.isSuccess()) {
                T data = resp.body.isEmpty() ? null : mapper.readValue(resp.body, clazz);
                return AltApiResponse.success(data, resp.code);
            }
            return AltApiResponse.error(resp.code, extractErrorCode(resp.body));
        } catch (Exception e) {
            android.util.Log.e(TAG, "post " + path + " EXCEPTION: " + e.getMessage(), e);
            return AltApiResponse.networkError();
        }
    }

    private AltApiResponse<Void> postVoid(String path, Object body) {
        try {
            String json = mapper.writeValueAsString(body);
            android.util.Log.d(TAG, "postVoid " + path + " body=" + json);
            AltApiClient.Response resp = client.post(path, json);
            if (resp.isNetworkError()) return AltApiResponse.networkError();
            android.util.Log.d(TAG, "postVoid " + path + " resp=" + resp.code + " body=" + resp.body);
            if (resp.isSuccess()) return AltApiResponse.success(null, resp.code);
            return AltApiResponse.error(resp.code, extractErrorCode(resp.body));
        } catch (Exception e) {
            return AltApiResponse.networkError();
        }
    }

    private <T> AltApiResponse<T> get(String path, Class<T> clazz) {
        try {
            AltApiClient.Response resp = client.get(path);
            if (resp.isNetworkError()) return AltApiResponse.networkError();
            if (resp.isSuccess()) {
                T data = resp.body.isEmpty() ? null : mapper.readValue(resp.body, clazz);
                return AltApiResponse.success(data, resp.code);
            }
            return AltApiResponse.error(resp.code, extractErrorCode(resp.body));
        } catch (Exception e) {
            return AltApiResponse.networkError();
        }
    }

    private <T> AltApiResponse<List<T>> getList(String path, TypeReference<List<T>> typeRef) {
        try {
            AltApiClient.Response resp = client.get(path);
            if (resp.isNetworkError()) return AltApiResponse.networkError();
            if (resp.isSuccess()) {
                List<T> data = mapper.readValue(resp.body, typeRef);
                return AltApiResponse.success(data, resp.code);
            }
            return AltApiResponse.error(resp.code, extractErrorCode(resp.body));
        } catch (Exception e) {
            return AltApiResponse.networkError();
        }
    }

    private String extractErrorCode(String body) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(body);
            if (node.has("errorCode")) return node.get("errorCode").asText();
            if (node.has("error")) return node.get("error").asText();
            if (node.has("code")) return node.get("code").asText();
        } catch (Exception ignored) {}
        return "unknown_error";
    }
}
