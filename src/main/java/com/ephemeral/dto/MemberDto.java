package com.ephemeral.dto;

import java.util.UUID;

public record MemberDto(UUID userId, String username, String displayName, String role, String avatarUrl) {
}
