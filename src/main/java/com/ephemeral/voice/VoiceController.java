package com.ephemeral.voice;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.guild.GuildService;
import com.ephemeral.web.ApiException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class VoiceController {

    private final GuildService guilds;
    private final LiveKitTokenService livekit;
    private final VoicePresenceService presence;

    public VoiceController(GuildService guilds, LiveKitTokenService livekit, VoicePresenceService presence) {
        this.guilds = guilds;
        this.livekit = livekit;
        this.presence = presence;
    }

    /** Issues a LiveKit join token for a voice channel, scoped by the user's role. */
    @PostMapping("/api/channels/{channelId}/voice-token")
    public LiveKitTokenService.Token voiceToken(@CurrentUser AuthUser user, @PathVariable UUID channelId) {
        UUID guildId = guilds.requireChannelMember(user.id(), channelId);
        if (!"voice".equals(guilds.channelType(channelId))) {
            throw ApiException.badRequest("not a voice channel");
        }
        boolean admin = guilds.role(user.id(), guildId).map("admin"::equals).orElse(false);
        // Enforce the user limit (admins bypass; someone already connected can reconnect).
        int limit = guilds.userLimitOf(channelId);
        if (limit > 0 && !admin
                && !presence.contains(channelId, user.id().toString())
                && presence.count(channelId) >= limit) {
            throw ApiException.badRequest("This voice channel is full (" + limit + " max)");
        }
        String room = "channel-" + channelId;
        return livekit.mint(user, room, admin);
    }
}
