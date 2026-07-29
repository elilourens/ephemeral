package com.ephemeral.dto;

import java.util.UUID;

/** A user as shown in lists (friends, join requests): identity + avatar only. */
public record UserBriefDto(UUID id, String username, String displayName, String avatarUrl) {
}
