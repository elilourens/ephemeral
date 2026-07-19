package com.ephemeral.dto;

import java.time.Instant;
import java.util.UUID;

/** One entry in a storage channel: a folder, or a file wrapping an upload. */
public record StorageItemDto(UUID id, UUID parentId, String kind, String name,
                             UUID ownerId, String ownerName,
                             String url, String contentType, Long sizeBytes,
                             Instant createdAt) {
}
