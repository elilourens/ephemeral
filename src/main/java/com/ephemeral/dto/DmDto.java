package com.ephemeral.dto;

import java.time.Instant;
import java.util.UUID;

/** A direct-message conversation as seen by one user: the DM channel + the other party. */
public record DmDto(UUID channelId, DmUser other, Instant lastMessageAt, boolean unread) {
    public record DmUser(UUID id, String username, String displayName) {}
}
