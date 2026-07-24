package com.chattera.profile.presence;

public enum PresenceStatus {
    ONLINE,
    OFFLINE,
    /** Redis was unreachable when presence was read; true online/offline state is not known. */
    UNKNOWN
}
