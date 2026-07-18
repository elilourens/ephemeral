package com.ephemeral.dto;

import java.util.List;
import java.util.UUID;

public record GuildDto(UUID id, String name, UUID ownerId, List<ChannelDto> channels) {
}
