package com.ephemeral.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService auth;
    private final JwtService jwt;

    public AuthController(AuthService auth, JwtService jwt) {
        this.auth = auth;
        this.jwt = jwt;
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 32) String username,
            @NotBlank @Size(min = 6, max = 200) String password,
            String displayName) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    @PostMapping("/register")
    public Map<String, Object> register(@org.springframework.web.bind.annotation.RequestBody
                                        @jakarta.validation.Valid RegisterRequest req) {
        AuthUser user = auth.register(req.username(), req.password(), req.displayName());
        return session(user);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody @jakarta.validation.Valid LoginRequest req) {
        AuthUser user = auth.login(req.username(), req.password());
        return session(user);
    }

    @GetMapping("/me")
    public AuthUser me(@CurrentUser AuthUser user) {
        return user;
    }

    private Map<String, Object> session(AuthUser user) {
        return Map.of("token", jwt.issue(user), "user", user);
    }
}
