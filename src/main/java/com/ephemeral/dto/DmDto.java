package com.ephemeral.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A direct-message conversation as seen by one user: 1:1 ({@code group=false},
 * one entry in {@code others}) or a group DM of up to 10 people. {@code name}
 * is the optional custom group name ("" = client joins member names);
 * {@code ownerId} is the group creator (kicks members, transfers on leave).
 */
public record DmDto(UUID channelId, boolean group, String name, UUID ownerId, List<DmUser> others,
                    Instant lastMessageAt, boolean unread) {
    public record DmUser(UUID id, String username, String displayName, String avatarUrl) {}
}
