package com.ephemeral.dto;

import java.time.Instant;
import java.util.UUID;

/** One message search result (light — enough to render a card + jump to it). */
public record SearchHitDto(
        UUID id,
        UUID channelId,
        String channelName,
        UUID guildId,
        UUID authorId,
        String authorName,
        String content,
        Instant createdAt) {
}
