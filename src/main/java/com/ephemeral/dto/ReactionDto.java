package com.ephemeral.dto;

/** An aggregated reaction on a message: the emoji, how many reacted, and whether you did. */
public record ReactionDto(String emoji, int count, boolean mine) {
}
