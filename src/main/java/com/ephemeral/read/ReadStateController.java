package com.ephemeral.read;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.ReadStateDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class ReadStateController {

    private final ReadStateService reads;

    public ReadStateController(ReadStateService reads) {
        this.reads = reads;
    }

    public record AckRequest(UUID lastReadId) {
    }

    @GetMapping("/api/guilds/{id}/read-state")
    public List<ReadStateDto> readState(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return reads.forGuild(user.id(), id);
    }

    @PostMapping("/api/channels/{id}/ack")
    public ResponseEntity<Void> ack(@CurrentUser AuthUser user, @PathVariable UUID id, @RequestBody AckRequest req) {
        reads.ack(user.id(), id, req.lastReadId());
        return ResponseEntity.noContent().build();
    }
}
