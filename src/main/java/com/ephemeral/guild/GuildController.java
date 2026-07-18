package com.ephemeral.guild;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.ChannelDto;
import com.ephemeral.dto.GuildDto;
import com.ephemeral.dto.MemberDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class GuildController {

    private final GuildService guilds;

    public GuildController(GuildService guilds) {
        this.guilds = guilds;
    }

    public record CreateGuildRequest(@NotBlank String name) {
    }

    public record CreateChannelRequest(@NotBlank String name, @NotBlank String type, boolean adminOnly) {
    }

    public record AdminOnlyRequest(boolean adminOnly) {
    }

    public record AddMemberRequest(@NotBlank String username) {
    }

    public record SetRoleRequest(@NotBlank String role) {
    }

    public record RenameRequest(@NotBlank String name) {
    }

    public record UpdateChannelRequest(String name, String topic, Integer slowModeSeconds, Integer userLimit) {
    }

    @PostMapping("/api/guilds")
    public GuildDto create(@CurrentUser AuthUser user, @RequestBody @Valid CreateGuildRequest req) {
        return guilds.createGuild(user.id(), req.name());
    }

    @GetMapping("/api/guilds")
    public List<GuildDto> myGuilds(@CurrentUser AuthUser user) {
        return guilds.listMyGuilds(user.id());
    }

    @GetMapping("/api/guilds/all")
    public List<GuildDto> allGuilds(@CurrentUser AuthUser user) {
        return guilds.listAllGuilds();
    }

    @GetMapping("/api/guilds/{id}")
    public GuildDto get(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return guilds.getGuild(user.id(), id);
    }

    @PostMapping("/api/guilds/{id}/join")
    public GuildDto join(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return guilds.joinGuild(user.id(), id);
    }

    @PatchMapping("/api/guilds/{id}")
    public GuildDto rename(@CurrentUser AuthUser user, @PathVariable UUID id,
                           @RequestBody @Valid RenameRequest req) {
        return guilds.renameGuild(user.id(), id, req.name());
    }

    @PostMapping("/api/guilds/{id}/leave")
    public ResponseEntity<Void> leave(@CurrentUser AuthUser user, @PathVariable UUID id) {
        guilds.leaveGuild(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/guilds/{id}")
    public ResponseEntity<Void> deleteGuild(@CurrentUser AuthUser user, @PathVariable UUID id) {
        guilds.deleteGuild(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/guilds/{id}/channels")
    public ChannelDto createChannel(@CurrentUser AuthUser user, @PathVariable UUID id,
                                    @RequestBody @Valid CreateChannelRequest req) {
        return guilds.createChannel(user.id(), id, req.name(), req.type(), req.adminOnly());
    }

    @PatchMapping("/api/channels/{id}")
    public ChannelDto updateChannel(@CurrentUser AuthUser user, @PathVariable UUID id,
                                    @RequestBody UpdateChannelRequest req) {
        return guilds.updateChannel(user.id(), id, req.name(), req.topic(), req.slowModeSeconds(), req.userLimit());
    }

    @PutMapping("/api/channels/{id}/admin-only")
    public ChannelDto setAdminOnly(@CurrentUser AuthUser user, @PathVariable UUID id,
                                   @RequestBody AdminOnlyRequest req) {
        return guilds.setChannelAdminOnly(user.id(), id, req.adminOnly());
    }

    @DeleteMapping("/api/channels/{id}")
    public ResponseEntity<Void> deleteChannel(@CurrentUser AuthUser user, @PathVariable UUID id) {
        guilds.deleteChannel(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/guilds/{id}/members")
    public List<MemberDto> members(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return guilds.listMembers(user.id(), id);
    }

    @PostMapping("/api/guilds/{id}/members")
    public MemberDto addMember(@CurrentUser AuthUser user, @PathVariable UUID id,
                               @RequestBody @Valid AddMemberRequest req) {
        return guilds.addMember(user.id(), id, req.username());
    }

    @PutMapping("/api/guilds/{id}/members/{userId}/role")
    public ResponseEntity<Void> setRole(@CurrentUser AuthUser user, @PathVariable UUID id,
                                        @PathVariable UUID userId, @RequestBody @Valid SetRoleRequest req) {
        guilds.setRole(user.id(), id, userId, req.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/guilds/{id}/members/{userId}")
    public ResponseEntity<Void> kick(@CurrentUser AuthUser user, @PathVariable UUID id,
                                     @PathVariable UUID userId) {
        guilds.kick(user.id(), id, userId);
        return ResponseEntity.noContent().build();
    }
}
