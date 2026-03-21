package org.thoughtcrime.securesms.altplatform.network.dto;

public class ResendCodeRequest {
    public String email;

    public ResendCodeRequest() {}

    public ResendCodeRequest(String email) {
        this.email = email;
    }
}
