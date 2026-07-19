package com.ephemeral.guild;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin moderation: bans, in-call enforcement, and the audit log. */
@RestController
public class ModerationController {

    private final ModerationService moderation;
    private final AuditService audit;
    private final GuildService guilds;

    public ModerationController(ModerationService moderation, AuditService audit, GuildService guilds) {
        this.moderation = moderation;
        this.audit = audit;
        this.guilds = guilds;
    }

    public record BanRequest(String reason) {}

    @PostMapping("/api/guilds/{guildId}/bans/{userId}")
    public void ban(@CurrentUser AuthUser user, @PathVariable UUID guildId, @PathVariable UUID userId,
                    @RequestBody(required = false) BanRequest req) {
        moderation.ban(user.id(), guildId, userId, req == null ? null : req.reason());
    }

    @DeleteMapping("/api/guilds/{guildId}/bans/{userId}")
    public void unban(@CurrentUser AuthUser user, @PathVariable UUID guildId, @PathVariable UUID userId) {
        moderation.unban(user.id(), guildId, userId);
    }

    @GetMapping("/api/guilds/{guildId}/bans")
    public List<Map<String, Object>> bans(@CurrentUser AuthUser user, @PathVariable UUID guildId) {
        return moderation.listBans(user.id(), guildId);
    }

    /** The admin log: moderation actions + server changes, newest first. */
    @GetMapping("/api/guilds/{guildId}/audit-log")
    public List<Map<String, Object>> auditLog(@CurrentUser AuthUser user, @PathVariable UUID guildId,
                                              @RequestParam(defaultValue = "100") int limit) {
        guilds.requireAdmin(user.id(), guildId);
        return audit.list(guildId, limit);
    }

    public record OnRequest(boolean on) {}

    @PostMapping("/api/channels/{channelId}/voice/{userId}/mute")
    public void mute(@CurrentUser AuthUser user, @PathVariable UUID channelId, @PathVariable UUID userId,
                     @RequestBody OnRequest req) {
        moderation.muteInVoice(user.id(), channelId, userId, req.on());
    }

    @PostMapping("/api/channels/{channelId}/voice/{userId}/deafen")
    public void deafen(@CurrentUser AuthUser user, @PathVariable UUID channelId, @PathVariable UUID userId,
                       @RequestBody OnRequest req) {
        moderation.deafenInVoice(user.id(), channelId, userId, req.on());
    }

    @PostMapping("/api/channels/{channelId}/voice/{userId}/disconnect")
    public void disconnect(@CurrentUser AuthUser user, @PathVariable UUID channelId, @PathVariable UUID userId) {
        moderation.disconnectFromVoice(user.id(), channelId, userId);
    }
}
