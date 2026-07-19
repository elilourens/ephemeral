package com.ephemeral.dto;

import java.util.UUID;

public record ChannelDto(UUID id, UUID guildId, String name, String type, int position,
                         boolean adminOnly, String topic, int slowModeSeconds, int userLimit,
                         Long retentionMs) {
}
