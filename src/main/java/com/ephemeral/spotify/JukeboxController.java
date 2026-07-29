package com.ephemeral.spotify;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/channels/{channelId}/jukebox")
public class JukeboxController {

    private final JukeboxService jukebox;

    public JukeboxController(JukeboxService jukebox) {
        this.jukebox = jukebox;
    }

    @GetMapping
    public Map<String, Object> state(@CurrentUser AuthUser user, @PathVariable UUID channelId) {
        return jukebox.state(user.id(), channelId);
    }

    @PostMapping("/summon")
    public Map<String, Object> summon(@CurrentUser AuthUser user, @PathVariable UUID channelId) {
        return jukebox.summon(user.id(), channelId);
    }

    @DeleteMapping
    public ResponseEntity<Void> dismiss(@CurrentUser AuthUser user, @PathVariable UUID channelId) {
        jukebox.dismiss(user.id(), channelId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public SpotifyService.SearchResults search(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                                               @RequestParam String q) {
        return jukebox.search(user.id(), channelId, q);
    }

    public record QueueTrackRequest(String uri, String name, String artists, Long durationMs, String imageUrl) {}

    @PostMapping("/queue")
    public ResponseEntity<Void> queueTrack(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                                           @RequestBody QueueTrackRequest req) {
        jukebox.queueTrack(user.id(), channelId, new SpotifyService.Track(
                req.uri(), req.name() == null ? "" : req.name(),
                req.artists() == null ? "" : req.artists(),
                req.durationMs() == null ? 0 : req.durationMs(), req.imageUrl()));
        return ResponseEntity.noContent().build();
    }

    public record QueuePlaylistRequest(String playlistId) {}

    @PostMapping("/queue-playlist")
    public Map<String, Object> queuePlaylist(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                                             @RequestBody QueuePlaylistRequest req) {
        int added = jukebox.queuePlaylist(user.id(), channelId, req.playlistId());
        return Map.of("added", added);
    }

    @DeleteMapping("/queue/{index}")
    public ResponseEntity<Void> removeFromQueue(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                                                @PathVariable int index) {
        jukebox.removeFromQueue(user.id(), channelId, index);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/skip")
    public ResponseEntity<Void> skip(@CurrentUser AuthUser user, @PathVariable UUID channelId) {
        jukebox.skip(user.id(), channelId);
        return ResponseEntity.noContent().build();
    }

    public record PauseRequest(boolean paused) {}

    @PostMapping("/pause")
    public ResponseEntity<Void> pause(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                                      @RequestBody PauseRequest req) {
        jukebox.pause(user.id(), channelId, req.paused());
        return ResponseEntity.noContent().build();
    }

    public record ListenRequest(boolean on) {}

    @PostMapping("/listen")
    public ResponseEntity<Void> listen(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                                       @RequestBody ListenRequest req) {
        jukebox.listen(user.id(), channelId, req.on());
        return ResponseEntity.noContent().build();
    }
}
