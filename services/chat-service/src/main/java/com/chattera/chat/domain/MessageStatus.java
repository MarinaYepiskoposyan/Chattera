package com.chattera.chat.domain;

/**
 * CHAT-104 only ever writes {@link #SENT} (server-accepted + persisted).
 * {@code DELIVERED}/{@code READ} are inherently real-time transitions and
 * belong to CHAT-107 - the column exists now so no migration is needed
 * later (see solution-architecture.md "Message status").
 */
public enum MessageStatus {
    SENT,
    DELIVERED,
    READ
}
