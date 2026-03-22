package org.thoughtcrime.securesms.altplatform.network.dto;

import java.util.List;

public class UserProfileResponse {
    public List<String> addr;
    public String name;
    public String username;
    public String fingerprint;
    public String public_key;

    public UserProfileResponse() {}

    public String primaryAddr() {
        return (addr != null && !addr.isEmpty()) ? addr.get(0) : null;
    }

    public String displayName() {
        return (name != null && !name.isEmpty()) ? name : username;
    }
}
