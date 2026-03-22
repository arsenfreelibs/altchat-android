package org.thoughtcrime.securesms.altplatform.network.dto;

import java.util.List;

public class RegisterRequest {
    public String username;
    public String email;
    public List<String> addr;
    public String displayName;
    public String publicKey;
    public String fingerprint;
    public String encryptedPrivateKey;

    public RegisterRequest() {}

    public RegisterRequest(String username, String email, List<String> addr, String displayName,
                           String publicKey, String fingerprint, String encryptedPrivateKey) {
        this.username = username;
        this.email = email;
        this.addr = addr;
        this.displayName = displayName;
        this.publicKey = publicKey;
        this.fingerprint = fingerprint;
        this.encryptedPrivateKey = encryptedPrivateKey;
    }
}
