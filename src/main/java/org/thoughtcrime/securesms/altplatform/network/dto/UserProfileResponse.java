package org.thoughtcrime.securesms.altplatform.network.dto;

import java.util.List;

public class UserProfileResponse {
    public List<String> addr;   // все DC-адреса пользователя (по одному на relay)
    public String name;         // displayName
    public String username;     // никнейм
    public String fingerprint;  // HEX fingerprint PGP-ключа
    public String public_key;   // armored OpenPGP public key

    public UserProfileResponse() {}

    public String primaryAddr() {
        return (addr != null && !addr.isEmpty()) ? addr.get(0) : null;
    }

    public String displayName() {
        return (name != null && !name.isEmpty()) ? name : primaryAddr();
    }
}
