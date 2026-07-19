package com.ephemeral.message;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.MessageDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class MessageController {

    private final MessageService messages;

    public MessageController(MessageService messages) {
        this.messages = messages;
    }

    public record SendMessageRequest(String content, List<UUID> attachmentIds, UUID replyToId, Boolean pingReply) {
    }

    public record EditMessageRequest(String content) {
    }

    public record ReactionRequest(String emoji) {
    }

    @GetMapping("/api/channels/{channelId}/messages")
    public List<MessageDto> list(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                                 @RequestParam(required = false) UUID before,
                                 @RequestParam(defaultValue = "50") int limit) {
        return messages.list(user.id(), channelId, before, limit);
    }

    @GetMapping("/api/channels/{channelId}/pins")
    public List<MessageDto> pins(@CurrentUser AuthUser user, @PathVariable UUID channelId) {
        return messages.listPins(user.id(), channelId);
    }

    @PostMapping("/api/channels/{channelId}/messages")
    public MessageDto send(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                           @RequestBody SendMessageRequest req) {
        return messages.send(user.id(), channelId, req.content(), req.attachmentIds(), req.replyToId(), req.pingReply());
    }

    @PatchMapping("/api/messages/{id}")
    public MessageDto edit(@CurrentUser AuthUser user, @PathVariable UUID id,
                           @RequestBody EditMessageRequest req) {
        return messages.edit(user.id(), id, req.content());
    }

    /** Prior versions of an edited message, newest first (vanishes with the message). */
    @GetMapping("/api/messages/{id}/history")
    public java.util.List<java.util.Map<String, Object>> history(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return messages.history(user.id(), id);
    }

    @PostMapping("/api/messages/{id}/react")
    public MessageDto react(@CurrentUser AuthUser user, @PathVariable UUID id, @RequestBody ReactionRequest req) {
        return messages.toggleReaction(user.id(), id, req.emoji());
    }

    @PostMapping("/api/messages/{id}/pin")
    public MessageDto pin(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return messages.setPin(user.id(), id, true);
    }

    @DeleteMapping("/api/messages/{id}/pin")
    public MessageDto unpin(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return messages.setPin(user.id(), id, false);
    }

    @PostMapping("/api/messages/{id}/save")
    public MessageDto save(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return messages.save(user.id(), id);
    }

    @DeleteMapping("/api/messages/{id}/save")
    public MessageDto unsave(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return messages.unsave(user.id(), id);
    }

    @DeleteMapping("/api/messages/{id}")
    public ResponseEntity<Void> delete(@CurrentUser AuthUser user, @PathVariable UUID id) {
        messages.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }
}
