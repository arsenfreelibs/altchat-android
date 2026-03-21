package org.thoughtcrime.securesms.altplatform.network.dto;

import java.util.List;

public class UserProfileResponse {
    public List<String> addrs;  // все DC-адреса пользователя (по одному на relay)
    public String name;         // displayName
    public String username;     // никнейм
    public String fingerprint;  // HEX fingerprint PGP-ключа
    public String publicKey;    // base64 OpenPGP public key

    public UserProfileResponse() {}

    public String primaryAddr() {
        return (addrs != null && !addrs.isEmpty()) ? addrs.get(0) : null;
    }

    public String displayName() {
        return (name != null && !name.isEmpty()) ? name : primaryAddr();
    }
}
