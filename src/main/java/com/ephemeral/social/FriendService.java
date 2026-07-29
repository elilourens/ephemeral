package com.ephemeral.social;

import com.ephemeral.dto.FriendsDto;
import com.ephemeral.dto.UserBriefDto;
import com.ephemeral.realtime.RealtimeService;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Friendships: a single canonical row per pair (user_lo < user_hi). A pending
 * row is a request from {@code requester}; accepting flips it. Both sides get a
 * {@code social_update} push on every change so open friends tabs stay live.
 */
@Service
public class FriendService {

    private final NamedParameterJdbcTemplate jdbc;
    private final RealtimeService realtime;

    public FriendService(NamedParameterJdbcTemplate jdbc, RealtimeService realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    private static UUID lo(UUID a, UUID b) { return a.compareTo(b) < 0 ? a : b; }
    private static UUID hi(UUID a, UUID b) { return a.compareTo(b) < 0 ? b : a; }

    public FriendsDto list(UUID me) {
        record Row(UserBriefDto user, String status, UUID requester) {}
        List<Row> rows = jdbc.query("""
                select u.id, u.username, u.display_name, u.avatar_id, f.status, f.requester
                from friendships f
                join users u on u.id = case when f.user_lo = :me then f.user_hi else f.user_lo end
                where f.user_lo = :me or f.user_hi = :me
                order by u.username
                """, Map.of("me", me),
                (rs, i) -> new Row(new UserBriefDto(
                        rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
                        rs.getObject("avatar_id", UUID.class) == null ? null : "/api/files/" + rs.getObject("avatar_id", UUID.class)),
                        rs.getString("status"), rs.getObject("requester", UUID.class)));
        List<UserBriefDto> friends = new ArrayList<>(), incoming = new ArrayList<>(), outgoing = new ArrayList<>();
        for (Row r : rows) {
            if ("accepted".equals(r.status())) {
                friends.add(r.user());
            } else if (me.equals(r.requester())) {
                outgoing.add(r.user());
            } else {
                incoming.add(r.user());
            }
        }
        return new FriendsDto(friends, incoming, outgoing);
    }

    /** Send a request by username; a crossing request from them becomes an instant friendship. */
    public FriendsDto sendRequest(UUID me, String username) {
        String uname = username == null ? "" : username.trim().toLowerCase().replaceFirst("^@", "");
        List<UUID> found = jdbc.queryForList("select id from users where username = :u",
                Map.of("u", uname), UUID.class);
        if (found.isEmpty()) {
            throw ApiException.notFound("no such user");
        }
        UUID other = found.get(0);
        if (other.equals(me)) {
            throw ApiException.badRequest("you can't friend yourself");
        }
        var existing = jdbc.query("""
                select status, requester from friendships where user_lo = :lo and user_hi = :hi
                """, Map.of("lo", lo(me, other), "hi", hi(me, other)),
                (rs, i) -> new Object[]{rs.getString("status"), rs.getObject("requester", UUID.class)});
        if (existing.isEmpty()) {
            jdbc.update("""
                    insert into friendships (user_lo, user_hi, requester, status)
                    values (:lo, :hi, :req, 'pending')
                    """, Map.of("lo", lo(me, other), "hi", hi(me, other), "req", me));
        } else if ("accepted".equals(existing.get(0)[0])) {
            throw ApiException.conflict("you are already friends");
        } else if (me.equals(existing.get(0)[1])) {
            throw ApiException.conflict("request already sent");
        } else {
            accept(me, other); // they asked first — this IS the acceptance
            return list(me);
        }
        realtime.socialUpdate(List.of(me, other), "friends", null);
        return list(me);
    }

    /** Accept a pending request that the other user sent me. */
    public FriendsDto accept(UUID me, UUID other) {
        int n = jdbc.update("""
                update friendships set status = 'accepted'
                where user_lo = :lo and user_hi = :hi and status = 'pending' and requester = :other
                """, Map.of("lo", lo(me, other), "hi", hi(me, other), "other", other));
        if (n == 0) {
            throw ApiException.notFound("no pending request from that user");
        }
        realtime.socialUpdate(List.of(me, other), "friends", null);
        return list(me);
    }

    /** Remove whatever exists between us: unfriend, decline incoming, or cancel outgoing. */
    public FriendsDto remove(UUID me, UUID other) {
        jdbc.update("delete from friendships where user_lo = :lo and user_hi = :hi",
                Map.of("lo", lo(me, other), "hi", hi(me, other)));
        realtime.socialUpdate(List.of(me, other), "friends", null);
        return list(me);
    }
}
