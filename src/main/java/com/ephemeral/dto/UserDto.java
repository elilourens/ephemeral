package com.ephemeral.dto;

import java.util.UUID;

public record UserDto(UUID id, String username, String displayName) {
}
