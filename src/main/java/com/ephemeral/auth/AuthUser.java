package com.ephemeral.auth;

import java.util.UUID;

/** The authenticated principal, reconstructed from the JWT on every request. */
public record AuthUser(UUID id, String username, String displayName) {
}
