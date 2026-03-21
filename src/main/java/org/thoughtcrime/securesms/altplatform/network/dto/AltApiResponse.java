package org.thoughtcrime.securesms.altplatform.network.dto;

import androidx.annotation.Nullable;

public class AltApiResponse<T> {
    public final T data;
    public final int httpCode;
    @Nullable
    public final String errorCode;

    public AltApiResponse(T data, int httpCode, @Nullable String errorCode) {
        this.data = data;
        this.httpCode = httpCode;
        this.errorCode = errorCode;
    }

    public boolean isSuccess() {
        return httpCode >= 200 && httpCode < 300;
    }

    public boolean isNetworkError() {
        return httpCode == 0;
    }

    public static <T> AltApiResponse<T> success(T data, int httpCode) {
        return new AltApiResponse<>(data, httpCode, null);
    }

    public static <T> AltApiResponse<T> error(int httpCode, String errorCode) {
        return new AltApiResponse<>(null, httpCode, errorCode);
    }

    public static <T> AltApiResponse<T> networkError() {
        return new AltApiResponse<>(null, 0, "network_error");
    }
}
