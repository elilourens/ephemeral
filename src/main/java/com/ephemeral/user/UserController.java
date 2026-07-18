package com.ephemeral.user;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.UserProfileDto;
import com.ephemeral.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class UserController {

    private final UserService users;
    private final ObjectMapper mapper;

    public UserController(UserService users, ObjectMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    public record UpdateProfileRequest(String displayName, String bio, String status, String customStatus) {
    }

    @GetMapping("/api/users/{id}")
    public UserProfileDto get(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return users.getProfile(id);
    }

    @PatchMapping("/api/users/me")
    public UserProfileDto updateMe(@CurrentUser AuthUser user, @RequestBody UpdateProfileRequest req) {
        return users.updateProfile(user.id(), req.displayName(), req.bio(), req.status(), req.customStatus());
    }

    /** Persisted per-user settings (mutes, notification + voice prefs) — survives restarts. */
    @GetMapping("/api/users/me/settings")
    public JsonNode getSettings(@CurrentUser AuthUser user) throws Exception {
        return mapper.readTree(users.getSettings(user.id()));
    }

    @PutMapping("/api/users/me/settings")
    public ResponseEntity<Void> putSettings(@CurrentUser AuthUser user, @RequestBody JsonNode body) throws Exception {
        if (body == null || !body.isObject()) {
            throw ApiException.badRequest("settings must be a JSON object");
        }
        String json = mapper.writeValueAsString(body);
        if (json.length() > 64_000) {
            throw ApiException.badRequest("settings too large");
        }
        users.setSettings(user.id(), json);
        return ResponseEntity.noContent().build();
    }

    /** Permanently delete the account and everything the user ever posted. */
    @DeleteMapping("/api/users/me")
    public ResponseEntity<Void> deleteMe(@CurrentUser AuthUser user) {
        users.deleteAccount(user.id());
        return ResponseEntity.noContent().build();
    }
}
