package org.thoughtcrime.securesms.altplatform.network.dto;

public class VerifyRequest {
    public String email;
    public String code;

    public VerifyRequest() {}

    public VerifyRequest(String email, String code) {
        this.email = email;
        this.code = code;
    }
}
