package com.ephemeral.voice;

import com.ephemeral.realtime.RealtimeService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks who is connected to each voice channel (fed by LiveKit webhooks) plus
 * their self-reported mute / deafen / screen-share state (fed by the client over
 * the app WebSocket), and pushes changes to all clients so the sidebar + tiles
 * can show voice presence and its indicators.
 */
@Service
public class VoicePresenceService {

    /** One participant's live voice state. */
    private static final class Part {
        volatile String name;
        volatile boolean muted;
        volatile boolean deafened;
        volatile boolean screen;

        Part(String name) {
            this.name = name;
        }
    }

    // room name ("channel-<uuid>") -> (userId -> Part)
    private final Map<String, Map<String, Part>> byRoom = new ConcurrentHashMap<>();
    private final RealtimeService realtime;

    public VoicePresenceService(RealtimeService realtime) {
        this.realtime = realtime;
    }

    public static UUID channelIdOf(String room) {
        if (room == null || !room.startsWith("channel-")) {
            return null;
        }
        try {
            return UUID.fromString(room.substring("channel-".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void joined(String room, String userId, String name) {
        if (channelIdOf(room) == null || userId == null) {
            return;
        }
        byRoom.computeIfAbsent(room, k -> new ConcurrentHashMap<>())
                .compute(userId, (k, prev) -> {
                    if (prev == null) {
                        return new Part(name == null ? userId : name);
                    }
                    if (name != null) {
                        prev.name = name; // keep any flags that arrived first
                    }
                    return prev;
                });
        broadcast(room);
    }

    /** Client-reported self mute/deafen/screen-share for the channel they're in. */
    public void setState(UUID channelId, String userId, boolean muted, boolean deafened, boolean screen) {
        if (channelId == null || userId == null) {
            return;
        }
        String room = "channel-" + channelId;
        // update-only: joins come from voice_join / webhooks (with a real name) —
        // never create a ghost entry whose display name is the raw user id
        Map<String, Part> parts = byRoom.get(room);
        Part part = parts == null ? null : parts.get(userId);
        if (part == null) {
            return;
        }
        part.muted = muted;
        part.deafened = deafened;
        part.screen = screen;
        broadcast(room);
    }

    public void left(String room, String userId) {
        Map<String, Part> m = byRoom.get(room);
        if (m != null && userId != null) {
            m.remove(userId);
            if (m.isEmpty()) {
                byRoom.remove(room);
            }
        }
        broadcast(room);
    }

    public void roomFinished(String room) {
        byRoom.remove(room);
        broadcast(room);
    }

    private void broadcast(String room) {
        UUID channelId = channelIdOf(room);
        if (channelId != null) {
            realtime.voicePresence(channelId, participantsOf(room));
        }
    }

    private List<Map<String, Object>> participantsOf(String room) {
        return byRoom.getOrDefault(room, Map.of()).entrySet().stream()
                .map(e -> {
                    Part p = e.getValue();
                    Map<String, Object> m = new HashMap<>();
                    m.put("userId", e.getKey());
                    m.put("name", p.name);
                    m.put("muted", p.muted);
                    m.put("deafened", p.deafened);
                    m.put("screen", p.screen);
                    return m;
                })
                .toList();
    }

    /** Number of participants currently in a voice channel (for user-limit checks). */
    public int count(UUID channelId) {
        Map<String, Part> m = byRoom.get("channel-" + channelId);
        return m == null ? 0 : m.size();
    }

    public boolean contains(UUID channelId, String userId) {
        Map<String, Part> m = byRoom.get("channel-" + channelId);
        return m != null && m.containsKey(userId);
    }

    /** Current presence for every active channel: { channelId -> [ {userId,name,...} ] }. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new HashMap<>();
        byRoom.forEach((room, m) -> {
            UUID channelId = channelIdOf(room);
            if (channelId != null) {
                out.put(channelId.toString(), participantsOf(room));
            }
        });
        return out;
    }
}
