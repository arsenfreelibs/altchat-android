package org.thoughtcrime.securesms.altplatform.network.dto;

import java.util.List;

public class RegisterRequest {
    public String username;
    public String email;             // аккаунтная почта (для OTP и восстановления)
    public List<String> addrs;       // DC-адреса аккаунта (по одному на relay-транспорт)
    public String displayName;       // nullable
    public String publicKey;         // base64 OpenPGP public key
    public String fingerprint;       // HEX fingerprint
    public String encryptedPrivateKey; // base64 AES-GCM encrypted blob

    public RegisterRequest() {}

    public RegisterRequest(String username, String email, List<String> addrs, String displayName,
                           String publicKey, String fingerprint, String encryptedPrivateKey) {
        this.username = username;
        this.email = email;
        this.addrs = addrs;
        this.displayName = displayName;
        this.publicKey = publicKey;
        this.fingerprint = fingerprint;
        this.encryptedPrivateKey = encryptedPrivateKey;
    }
}
