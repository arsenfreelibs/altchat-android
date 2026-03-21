package org.thoughtcrime.securesms.altplatform.network.dto;

public class PrivateKeyResponse {
    public String encryptedPrivateKey; // base64 AES-GCM encrypted blob

    public PrivateKeyResponse() {}
}
