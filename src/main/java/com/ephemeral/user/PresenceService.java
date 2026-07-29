package com.ephemeral.user;

import com.ephemeral.realtime.RealtimeService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Online presence: a user is online while they hold ≥1 WebSocket connection.
 * Combines with their chosen status (online/idle/dnd), custom status, and the
 * ephemeral "listening to" line (set by integrations like Spotify — in-memory
 * only, never persisted), and broadcasts changes to all clients.
 */
@Service
public class PresenceService {

    private final RealtimeService realtime;
    private final NamedParameterJdbcTemplate jdbc;
    private final Map<UUID, Integer> connections = new ConcurrentHashMap<>();
    private final Map<UUID, String> listening = new ConcurrentHashMap<>();

    public PresenceService(RealtimeService realtime, NamedParameterJdbcTemplate jdbc) {
        this.realtime = realtime;
        this.jdbc = jdbc;
    }

    public synchronized void connected(UUID userId) {
        int count = connections.merge(userId, 1, Integer::sum);
        if (count == 1) {
            String[] st = statusOf(userId);
            realtime.presenceUpdate(userId, true, st[0], st[1], listening.get(userId));
        }
    }

    public synchronized void disconnected(UUID userId) {
        Integer count = connections.get(userId);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            connections.remove(userId);
            realtime.presenceUpdate(userId, false, null, null, null);
        } else {
            connections.put(userId, count - 1);
        }
    }

    /** Re-broadcast a user's status if they're online (e.g. after they change it). */
    public void statusChanged(UUID userId) {
        if (connections.containsKey(userId)) {
            String[] st = statusOf(userId);
            realtime.presenceUpdate(userId, true, st[0], st[1], listening.get(userId));
        }
    }

    /**
     * Set (or clear, with null) what a user is listening to. Broadcasts only on
     * change, and only while they're online — the poller that feeds this skips
     * offline users anyway.
     */
    public void setListening(UUID userId, String value) {
        String prev = value == null ? listening.remove(userId) : listening.put(userId, value);
        if (!Objects.equals(prev, value) && connections.containsKey(userId)) {
            String[] st = statusOf(userId);
            realtime.presenceUpdate(userId, true, st[0], st[1], value);
        }
    }

    /** Users currently holding at least one socket (for pollers). */
    public Set<UUID> onlineUsers() {
        return Set.copyOf(connections.keySet());
    }

    /** { userId -> {status, customStatus, listening} } for everyone currently online. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new HashMap<>();
        for (UUID userId : connections.keySet()) {
            String[] st = statusOf(userId);
            Map<String, Object> m = new HashMap<>();
            m.put("status", st[0]);
            m.put("customStatus", st[1]);
            m.put("listening", listening.get(userId));
            out.put(userId.toString(), m);
        }
        return out;
    }

    private String[] statusOf(UUID userId) {
        var rows = jdbc.query("select status, custom_status from users where id = :id",
                Map.of("id", userId), (rs, i) -> new String[]{rs.getString("status"), rs.getString("custom_status")});
        return rows.isEmpty() ? new String[]{"online", null} : rows.get(0);
    }
}
