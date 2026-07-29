package com.ephemeral.social;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.GuildDto;
import com.ephemeral.dto.InviteDto;
import com.ephemeral.dto.UserBriefDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class InviteController {

    private final InviteService invites;

    public InviteController(InviteService invites) {
        this.invites = invites;
    }

    public record InviteRequest(@NotBlank String username) {
    }

    // ---- invites -----------------------------------------------------------

    @PostMapping("/api/guilds/{id}/invites")
    public InviteDto create(@CurrentUser AuthUser user, @PathVariable UUID id,
                            @RequestBody @Valid InviteRequest req) {
        return invites.createInvite(user.id(), id, req.username());
    }

    @GetMapping("/api/invites")
    public List<InviteDto> mine(@CurrentUser AuthUser user) {
        return invites.myInvites(user.id());
    }

    @PostMapping("/api/invites/{id}/accept")
    public GuildDto accept(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return invites.acceptInvite(user.id(), id);
    }

    @DeleteMapping("/api/invites/{id}")
    public ResponseEntity<Void> decline(@CurrentUser AuthUser user, @PathVariable UUID id) {
        invites.deleteInvite(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    // ---- join requests ------------------------------------------------------

    @PostMapping("/api/guilds/{id}/join-requests")
    public Map<String, Object> requestJoin(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return invites.requestJoin(user.id(), id);
    }

    @GetMapping("/api/me/join-requests")
    public List<UUID> myJoinRequests(@CurrentUser AuthUser user) {
        return invites.myJoinRequests(user.id());
    }

    @GetMapping("/api/guilds/{id}/join-requests")
    public List<UserBriefDto> listJoinRequests(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return invites.listJoinRequests(user.id(), id);
    }

    @PostMapping("/api/guilds/{id}/join-requests/{userId}/approve")
    public ResponseEntity<Void> approve(@CurrentUser AuthUser user, @PathVariable UUID id,
                                        @PathVariable UUID userId) {
        invites.approveJoinRequest(user.id(), id, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/guilds/{id}/join-requests/{userId}")
    public ResponseEntity<Void> deny(@CurrentUser AuthUser user, @PathVariable UUID id,
                                     @PathVariable UUID userId) {
        invites.deleteJoinRequest(user.id(), id, userId);
        return ResponseEntity.noContent().build();
    }
}
