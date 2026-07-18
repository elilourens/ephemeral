package com.ephemeral.dto;

import java.util.UUID;

/** Per-channel read state: how many unread mentions, and the newest vs last-read ids. */
public record ReadStateDto(UUID channelId, int mentionCount, UUID lastReadId, UUID latestId) {
}
