package com.ephemeral.voice;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.guild.GuildService;
import com.ephemeral.realtime.RealtimeService;
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
    private final RealtimeService realtime;

    public VoiceController(GuildService guilds, LiveKitTokenService livekit,
                           VoicePresenceService presence, RealtimeService realtime) {
        this.guilds = guilds;
        this.livekit = livekit;
        this.presence = presence;
        this.realtime = realtime;
    }

    /** Issues a LiveKit join token for a voice channel, scoped by the user's role. */
    @PostMapping("/api/channels/{channelId}/voice-token")
    public LiveKitTokenService.Token voiceToken(@CurrentUser AuthUser user, @PathVariable UUID channelId) {
        UUID guildId = guilds.requireChannelMember(user.id(), channelId);
        String type = guilds.channelType(channelId);
        if (!"voice".equals(type) && !"dm".equals(type)) { // DMs host calls too
            throw ApiException.badRequest("not a voice channel");
        }
        boolean admin = guildId != null && guilds.role(user.id(), guildId).map("admin"::equals).orElse(false);
        // Enforce the user limit (admins bypass; someone already connected can reconnect).
        int limit = guilds.userLimitOf(channelId);
        if (limit > 0 && !admin
                && !presence.contains(channelId, user.id().toString())
                && presence.count(channelId) >= limit) {
            throw ApiException.badRequest("This voice channel is full (" + limit + " max)");
        }
        // Ring: the first person joining a DM call notifies the other participants.
        if (guildId == null && presence.count(channelId) == 0) {
            realtime.dmCallStarted(channelId, user, guilds.dmMemberIds(channelId));
        }
        String room = "channel-" + channelId;
        return livekit.mint(user, room, admin);
    }
}
