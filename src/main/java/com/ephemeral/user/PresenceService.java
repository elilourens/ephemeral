package com.ephemeral.user;

import com.ephemeral.realtime.RealtimeService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Online presence: a user is online while they hold ≥1 WebSocket connection.
 * Combines with their chosen status (online/idle/dnd) and custom status, and
 * broadcasts changes to all clients.
 */
@Service
public class PresenceService {

    private final RealtimeService realtime;
    private final NamedParameterJdbcTemplate jdbc;
    private final Map<UUID, Integer> connections = new ConcurrentHashMap<>();

    public PresenceService(RealtimeService realtime, NamedParameterJdbcTemplate jdbc) {
        this.realtime = realtime;
        this.jdbc = jdbc;
    }

    public synchronized void connected(UUID userId) {
        int count = connections.merge(userId, 1, Integer::sum);
        if (count == 1) {
            String[] st = statusOf(userId);
            realtime.presenceUpdate(userId, true, st[0], st[1]);
        }
    }

    public synchronized void disconnected(UUID userId) {
        Integer count = connections.get(userId);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            connections.remove(userId);
            realtime.presenceUpdate(userId, false, null, null);
        } else {
            connections.put(userId, count - 1);
        }
    }

    /** Re-broadcast a user's status if they're online (e.g. after they change it). */
    public void statusChanged(UUID userId) {
        if (connections.containsKey(userId)) {
            String[] st = statusOf(userId);
            realtime.presenceUpdate(userId, true, st[0], st[1]);
        }
    }

    /** { userId -> {status, customStatus} } for everyone currently online. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new HashMap<>();
        for (UUID userId : connections.keySet()) {
            String[] st = statusOf(userId);
            Map<String, Object> m = new HashMap<>();
            m.put("status", st[0]);
            m.put("customStatus", st[1]);
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
