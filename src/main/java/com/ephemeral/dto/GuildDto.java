package com.ephemeral.dto;

import java.util.List;
import java.util.UUID;

/** {@code iconUrl} is null when the server has no custom icon (client shows initials). */
public record GuildDto(UUID id, String name, UUID ownerId, String iconUrl, List<ChannelDto> channels) {
}
