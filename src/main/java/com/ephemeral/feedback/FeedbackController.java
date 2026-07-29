package com.ephemeral.feedback;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class FeedbackController {

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    public record SubmitRequest(@NotBlank String body) {
    }

    @PostMapping("/api/feedback")
    public ResponseEntity<Void> submit(@CurrentUser AuthUser user, @RequestBody @Valid SubmitRequest req) {
        feedback.submit(user.id(), req.body());
        return ResponseEntity.noContent().build();
    }

    /**
     * Everyone gets 200; only the operator gets items (and operator=true). A 403
     * here would show up as console noise on every settings open for regular users.
     */
    @GetMapping("/api/feedback")
    public Map<String, Object> list(@CurrentUser AuthUser user) {
        boolean operator = feedback.isOperator(user.id());
        return Map.of("operator", operator,
                "items", operator ? feedback.list(user.id()) : List.of());
    }

    @DeleteMapping("/api/feedback/{id}")
    public ResponseEntity<Void> delete(@CurrentUser AuthUser user, @PathVariable UUID id) {
        feedback.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }
}
