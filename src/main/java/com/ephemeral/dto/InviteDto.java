package com.ephemeral.dto;

import java.util.UUID;

/** A pending server invite as the invitee sees it. */
public record InviteDto(UUID id, UUID guildId, String guildName, String guildIconUrl, String inviterName) {
}
