package com.ephemeral.dto;

import java.time.Instant;
import java.util.UUID;

public record UserProfileDto(
        UUID id,
        String username,
        String displayName,
        String bio,
        String status,
        String customStatus,
        Instant createdAt) {
}
