package org.thoughtcrime.securesms.altplatform.network;

import android.content.Context;
import android.util.Log;

import org.thoughtcrime.securesms.altplatform.storage.AltTokenStorage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Low-level HTTP client for Alt Platform API.
 * Adds Authorization: Bearer if JWT is available.
 * Timeout: 15 seconds.
 */
public class AltApiClient {

    private static final String TAG = AltApiClient.class.getSimpleName();
    private static final int TIMEOUT_MS = 15_000;

    private final Context context;
    private final String baseUrl;
    private final int accountId;

    public AltApiClient(Context context, String baseUrl, int accountId) {
        this.context = context.getApplicationContext();
        this.baseUrl = baseUrl;
        this.accountId = accountId;
    }

    public Response post(String path, String jsonBody) {
        return execute("POST", path, jsonBody);
    }

    public Response get(String path) {
        return execute("GET", path, null);
    }

    private Response execute(String method, String path, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            String token = AltTokenStorage.getToken(context, accountId);
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            if (jsonBody != null) {
                conn.setDoOutput(true);
                byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
                OutputStream out = conn.getOutputStream();
                out.write(body);
                out.flush();
                out.close();
            }

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String responseBody = stream != null ? readStream(stream) : "";
            Log.d(TAG, method + " " + path + " -> " + code + " body=" + responseBody);
            return new Response(code, responseBody);
        } catch (Exception e) {
            Log.e(TAG, method + " " + path + " -> NETWORK ERROR: " + e.getMessage(), e);
            return new Response(0, "");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readStream(InputStream stream) throws IOException {
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = stream.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        stream.close();
        return sb.toString();
    }

    public static class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        public boolean isSuccess() {
            return code >= 200 && code < 300;
        }

        public boolean isNetworkError() {
            return code == 0;
        }
    }
}
