package com.ephemeral.dm;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.DmDto;
import com.ephemeral.web.ApiException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class DmController {

    private final DmService dms;

    public DmController(DmService dms) {
        this.dms = dms;
    }

    /** All of my DM conversations, most recently active first. */
    @GetMapping("/api/dms")
    public List<DmDto> list(@CurrentUser AuthUser user) {
        return dms.list(user.id());
    }

    public record OpenDmRequest(UUID userId, String username, List<String> usernames, String name) {}

    /**
     * Open a conversation. One person (userId or username) = idempotent 1:1;
     * a usernames list = a new group DM (3..10 people incl. you, optional name).
     */
    @PostMapping("/api/dms")
    public DmDto open(@CurrentUser AuthUser user, @RequestBody OpenDmRequest req) {
        if (req != null && req.usernames() != null && !req.usernames().isEmpty()) {
            if (req.usernames().size() == 1) {
                return dms.openByUsername(user.id(), req.usernames().get(0));
            }
            return dms.createGroup(user.id(), req.usernames(), req.name());
        }
        if (req != null && req.userId() != null) {
            return dms.open(user.id(), req.userId());
        }
        if (req != null && req.username() != null && !req.username().isBlank()) {
            return dms.openByUsername(user.id(), req.username().trim());
        }
        throw ApiException.badRequest("userId, username or usernames is required");
    }

    public record MemberRequest(String username) {}

    /** Add someone. Group: joins in place. 1:1: spawns a NEW group (the 1:1 survives). */
    @PostMapping("/api/dms/{channelId}/members")
    public DmDto addMember(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                           @RequestBody MemberRequest req) {
        return dms.addMember(user.id(), channelId, req == null ? null : req.username());
    }

    /** Remove someone from a group DM (owner only). */
    @DeleteMapping("/api/dms/{channelId}/members/{userId}")
    public void kick(@CurrentUser AuthUser user, @PathVariable UUID channelId, @PathVariable UUID userId) {
        dms.kick(user.id(), channelId, userId);
    }

    /** Leave a group DM. */
    @PostMapping("/api/dms/{channelId}/leave")
    public void leave(@CurrentUser AuthUser user, @PathVariable UUID channelId) {
        dms.leave(user.id(), channelId);
    }

    public record RenameRequest(String name) {}

    /** Rename a group DM (any member). */
    @PatchMapping("/api/dms/{channelId}")
    public DmDto rename(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                        @RequestBody RenameRequest req) {
        return dms.rename(user.id(), channelId, req == null ? "" : req.name());
    }
}
