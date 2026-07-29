package com.ephemeral.spotify;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.web.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class SpotifyController {

    private final SpotifyService spotify;

    public SpotifyController(SpotifyService spotify) {
        this.spotify = spotify;
    }

    @GetMapping("/api/spotify/status")
    public Map<String, Object> status(@CurrentUser AuthUser user) {
        boolean configured = spotify.configured();
        return Map.of("configured", configured,
                "connected", configured && spotify.connected(user.id()));
    }

    /** Where to send the browser to grant access (client navigates there). */
    @GetMapping("/api/spotify/connect-url")
    public Map<String, String> connectUrl(@CurrentUser AuthUser user) {
        return Map.of("url", spotify.connectUrl(user.id()));
    }

    /** OAuth redirect target (unauthenticated; the state nonce identifies the user). */
    @GetMapping("/api/spotify/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        String frag;
        if (error != null) {
            frag = "spotify-denied";
        } else {
            try {
                spotify.handleCallback(code, state);
                frag = "spotify-connected";
            } catch (ApiException e) {
                frag = "spotify-error";
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/#" + frag).build();
    }

    @DeleteMapping("/api/spotify")
    public ResponseEntity<Void> disconnect(@CurrentUser AuthUser user) {
        spotify.disconnect(user.id());
        return ResponseEntity.noContent().build();
    }
}
