package com.ephemeral.dto;

import java.util.UUID;

public record AttachmentDto(UUID id, String filename, String contentType, long sizeBytes, String url,
                            Integer durationMs, String waveform) {

    public AttachmentDto(UUID id, String filename, String contentType, long sizeBytes, String url) {
        this(id, filename, contentType, sizeBytes, url, null, null);
    }
}
