package com.ephemeral.spotify;

import com.ephemeral.guild.GuildService;
import com.ephemeral.realtime.RealtimeService;
import com.ephemeral.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The "music bot": a per-voice-channel shared queue that plays Spotify through
 * each listener's OWN Spotify device (Connect API). Spotify's audio can't be
 * streamed into a call by a server (DRM + ToS) — instead, everyone who toggles
 * Listen Along gets play/pause/seek commands on their linked account, so the
 * group stays in sync. All state is in-memory: a restart silences the jukebox,
 * which suits the app's ephemerality.
 */
@Service
public class JukeboxService {

    private static final Logger log = LoggerFactory.getLogger(JukeboxService.class);

    private final SpotifyService spotify;
    private final GuildService guilds;
    private final RealtimeService realtime;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jukebox");
                t.setDaemon(true);
                return t;
            });

    /** One channel's jukebox. Guarded by synchronized(this) per instance. */
    static class Box {
        final UUID channelId;
        final UUID guildId;
        final List<SpotifyService.Track> queue = new ArrayList<>();
        final Map<UUID, String> listeners = new LinkedHashMap<>(); // userId -> last sync error or null
        SpotifyService.Track now;
        Instant startedAt;      // when `now` (re)started at position `basePositionMs`
        long basePositionMs;
        boolean paused;
        ScheduledFuture<?> advance;

        Box(UUID channelId, UUID guildId) {
            this.channelId = channelId;
            this.guildId = guildId;
        }

        long positionMs() {
            if (now == null) {
                return 0;
            }
            if (paused) {
                return basePositionMs;
            }
            return Math.min(now.durationMs(),
                    basePositionMs + (Instant.now().toEpochMilli() - startedAt.toEpochMilli()));
        }
    }

    private final Map<UUID, Box> boxes = new ConcurrentHashMap<>();

    public JukeboxService(SpotifyService spotify, GuildService guilds, RealtimeService realtime) {
        this.spotify = spotify;
        this.guilds = guilds;
        this.realtime = realtime;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private Box require(UUID channelId) {
        Box box = boxes.get(channelId);
        if (box == null) {
            throw ApiException.notFound("no jukebox in this channel — summon it first");
        }
        return box;
    }

    /** Member access + voice-channel check shared by every endpoint. */
    private UUID checkChannel(UUID userId, UUID channelId) {
        UUID guildId = guilds.requireChannelMember(userId, channelId);
        if (guildId == null || !"voice".equals(guilds.channelType(channelId))) {
            throw ApiException.badRequest("the jukebox lives in server voice channels");
        }
        return guildId;
    }

    public Map<String, Object> summon(UUID userId, UUID channelId) {
        spotify.requireConfigured();
        UUID guildId = checkChannel(userId, channelId);
        boxes.computeIfAbsent(channelId, id -> new Box(id, guildId));
        push(channelId);
        return state(userId, channelId);
    }

    public void dismiss(UUID userId, UUID channelId) {
        checkChannel(userId, channelId);
        Box box = boxes.remove(channelId);
        if (box != null) {
            synchronized (box) {
                if (box.advance != null) {
                    box.advance.cancel(false);
                }
                for (UUID listener : box.listeners.keySet()) {
                    try {
                        spotify.pauseUserDevice(listener);
                    } catch (Exception ignored) {
                    }
                }
            }
            push(channelId);
        }
    }

    public Map<String, Object> state(UUID userId, UUID channelId) {
        checkChannel(userId, channelId);
        Box box = boxes.get(channelId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", box != null);
        out.put("configured", spotify.configured());
        out.put("linked", spotify.configured() && spotify.connected(userId));
        if (box == null) {
            return out;
        }
        synchronized (box) {
            out.put("queue", box.queue.stream().map(JukeboxService::trackMap).toList());
            if (box.now != null) {
                Map<String, Object> now = trackMap(box.now);
                now.put("positionMs", box.positionMs());
                now.put("paused", box.paused);
                out.put("now", now);
            }
            List<Map<String, Object>> listeners = new ArrayList<>();
            for (var e : box.listeners.entrySet()) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("userId", e.getKey());
                l.put("error", e.getValue());
                listeners.add(l);
            }
            out.put("listeners", listeners);
            out.put("listening", box.listeners.containsKey(userId));
        }
        return out;
    }

    private static Map<String, Object> trackMap(SpotifyService.Track t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", t.uri());
        m.put("name", t.name());
        m.put("artists", t.artists());
        m.put("durationMs", t.durationMs());
        m.put("imageUrl", t.imageUrl());
        return m;
    }

    public SpotifyService.SearchResults search(UUID userId, UUID channelId, String query) {
        checkChannel(userId, channelId);
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            throw ApiException.badRequest("search for something first");
        }
        return spotify.search(q);
    }

    /** Queue one track (from search results, passed back verbatim). */
    public void queueTrack(UUID userId, UUID channelId, SpotifyService.Track track) {
        checkChannel(userId, channelId);
        if (track == null || track.uri() == null || !track.uri().startsWith("spotify:track:")) {
            throw ApiException.badRequest("that's not a Spotify track");
        }
        Box box = require(channelId);
        synchronized (box) {
            if (box.queue.size() >= 500) {
                throw ApiException.badRequest("the queue is full (500)");
            }
            box.queue.add(track);
            if (box.now == null) {
                startNext(box);
            }
        }
        push(channelId);
    }

    /** Queue a whole playlist (first 100 tracks). */
    public int queuePlaylist(UUID userId, UUID channelId, String playlistId) {
        checkChannel(userId, channelId);
        Box box = require(channelId);
        List<SpotifyService.Track> tracks = spotify.playlistTracks(playlistId);
        synchronized (box) {
            int room = Math.max(0, 500 - box.queue.size());
            List<SpotifyService.Track> adding = tracks.subList(0, Math.min(room, tracks.size()));
            box.queue.addAll(adding);
            if (box.now == null && !box.queue.isEmpty()) {
                startNext(box);
            }
            push(channelId);
            return adding.size();
        }
    }

    public void removeFromQueue(UUID userId, UUID channelId, int index) {
        checkChannel(userId, channelId);
        Box box = require(channelId);
        synchronized (box) {
            if (index >= 0 && index < box.queue.size()) {
                box.queue.remove(index);
            }
        }
        push(channelId);
    }

    public void skip(UUID userId, UUID channelId) {
        checkChannel(userId, channelId);
        Box box = require(channelId);
        synchronized (box) {
            startNext(box);
        }
        push(channelId);
    }

    public void pause(UUID userId, UUID channelId, boolean pause) {
        checkChannel(userId, channelId);
        Box box = require(channelId);
        synchronized (box) {
            if (box.now == null || box.paused == pause) {
                return;
            }
            if (pause) {
                box.basePositionMs = box.positionMs();
                box.paused = true;
                if (box.advance != null) {
                    box.advance.cancel(false);
                }
                for (UUID listener : List.copyOf(box.listeners.keySet())) {
                    trySync(box, listener, () -> spotify.pauseUserDevice(listener));
                }
            } else {
                box.paused = false;
                box.startedAt = Instant.now();
                scheduleAdvance(box);
                syncAll(box);
            }
        }
        push(channelId);
    }

    /** Toggle Listen Along: on = this user's Spotify starts mirroring the box. */
    public void listen(UUID userId, UUID channelId, boolean on) {
        checkChannel(userId, channelId);
        Box box = require(channelId);
        if (on && !spotify.connected(userId)) {
            throw ApiException.badRequest("connect Spotify first (Settings → Connections)");
        }
        synchronized (box) {
            if (on) {
                box.listeners.put(userId, null);
                if (box.now != null && !box.paused) {
                    trySync(box, userId, () ->
                            spotify.playOnUserDevice(userId, box.now.uri(), box.positionMs()));
                }
            } else {
                box.listeners.remove(userId);
                try {
                    spotify.pauseUserDevice(userId);
                } catch (Exception ignored) {
                }
            }
        }
        push(channelId);
    }

    // ---- internals ----------------------------------------------------------

    /** Pop the queue into `now` (or stop when empty) and sync everyone. Call holding the lock. */
    private void startNext(Box box) {
        if (box.advance != null) {
            box.advance.cancel(false);
        }
        if (box.queue.isEmpty()) {
            box.now = null;
            box.paused = false;
            for (UUID listener : List.copyOf(box.listeners.keySet())) {
                trySync(box, listener, () -> spotify.pauseUserDevice(listener));
            }
            return;
        }
        box.now = box.queue.remove(0);
        box.basePositionMs = 0;
        box.startedAt = Instant.now();
        box.paused = false;
        scheduleAdvance(box);
        syncAll(box);
    }

    private void scheduleAdvance(Box box) {
        long remaining = Math.max(500, box.now.durationMs() - box.positionMs() + 400);
        box.advance = scheduler.schedule(() -> {
            synchronized (box) {
                if (boxes.get(box.channelId) == box && box.now != null && !box.paused) {
                    startNext(box);
                }
            }
            push(box.channelId);
        }, remaining, TimeUnit.MILLISECONDS);
    }

    private void syncAll(Box box) {
        for (UUID listener : List.copyOf(box.listeners.keySet())) {
            trySync(box, listener, () ->
                    spotify.playOnUserDevice(listener, box.now.uri(), box.positionMs()));
        }
    }

    /** Run one listener's sync command, remembering their per-user error for the UI. */
    private void trySync(Box box, UUID listener, Runnable command) {
        try {
            command.run();
            box.listeners.replace(listener, null);
        } catch (ApiException e) {
            box.listeners.replace(listener, e.getMessage());
            log.debug("jukebox sync failed for {}: {}", listener, e.getMessage());
        } catch (Exception e) {
            box.listeners.replace(listener, "sync failed");
            log.debug("jukebox sync failed for {}", listener, e);
        }
    }

    private void push(UUID channelId) {
        Box box = boxes.get(channelId);
        realtime.jukeboxUpdate(channelId, box == null ? null : box.guildId);
    }
}
