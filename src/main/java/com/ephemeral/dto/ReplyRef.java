package com.ephemeral.dto;

import java.util.UUID;

/** A compact preview of the message being replied to. */
public record ReplyRef(UUID id, UUID authorId, String authorName, String content) {
}
