package org.thoughtcrime.securesms.altplatform.network.dto;

public class RestoreRequest {
    public String username;
    public String email;

    public RestoreRequest() {}

    public RestoreRequest(String username, String email) {
        this.username = username;
        this.email = email;
    }
}
