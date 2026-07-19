package com.ephemeral.dm;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.DmDto;
import com.ephemeral.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
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

    public record OpenDmRequest(UUID userId, String username) {}

    /** Open (or create) a DM with another user by id or username; idempotent per pair. */
    @PostMapping("/api/dms")
    public DmDto open(@CurrentUser AuthUser user, @RequestBody OpenDmRequest req) {
        if (req != null && req.userId() != null) {
            return dms.open(user.id(), req.userId());
        }
        if (req != null && req.username() != null && !req.username().isBlank()) {
            return dms.openByUsername(user.id(), req.username().trim());
        }
        throw ApiException.badRequest("userId or username is required");
    }
}
