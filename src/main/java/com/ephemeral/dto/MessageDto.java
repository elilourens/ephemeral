package com.ephemeral.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageDto(
        UUID id,
        UUID channelId,
        UUID authorId,
        String authorName,
        String content,
        boolean saved,
        boolean pinned,
        Instant createdAt,
        Instant editedAt,
        List<AttachmentDto> attachments,
        ReplyRef replyTo,
        List<ReactionDto> reactions,
        List<UUID> mentions) {
}
