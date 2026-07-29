package com.ephemeral.social;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.FriendsDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class FriendController {

    private final FriendService friends;

    public FriendController(FriendService friends) {
        this.friends = friends;
    }

    public record FriendRequest(@NotBlank String username) {
    }

    @GetMapping("/api/friends")
    public FriendsDto list(@CurrentUser AuthUser user) {
        return friends.list(user.id());
    }

    @PostMapping("/api/friends")
    public FriendsDto send(@CurrentUser AuthUser user, @RequestBody @Valid FriendRequest req) {
        return friends.sendRequest(user.id(), req.username());
    }

    @PostMapping("/api/friends/{userId}/accept")
    public FriendsDto accept(@CurrentUser AuthUser user, @PathVariable UUID userId) {
        return friends.accept(user.id(), userId);
    }

    @DeleteMapping("/api/friends/{userId}")
    public FriendsDto remove(@CurrentUser AuthUser user, @PathVariable UUID userId) {
        return friends.remove(user.id(), userId);
    }
}
