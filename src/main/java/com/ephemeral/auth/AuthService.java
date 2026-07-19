package com.ephemeral.auth;

import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final NamedParameterJdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Does this user still exist? A signed JWT alone isn't enough — the account
     * may have been deleted (or the DB reset under a dev boot); acting on a
     * ghost id causes FK violations instead of a clean re-login.
     */
    public boolean userExists(UUID id) {
        Integer n = jdbc.queryForObject("select count(*) from users where id = :id",
                Map.of("id", id), Integer.class);
        return n != null && n > 0;
    }

    public AuthUser register(String username, String password, String displayName) {
        String uname = username.trim().toLowerCase();
        Integer exists = jdbc.queryForObject(
                "select count(*) from users where username = :u",
                Map.of("u", uname), Integer.class);
        if (exists != null && exists > 0) {
            throw ApiException.conflict("username already taken");
        }
        UUID id = Ids.newId();
        String dname = (displayName == null || displayName.isBlank()) ? username.trim() : displayName.trim();
        jdbc.update("""
                insert into users (id, username, password_hash, display_name)
                values (:id, :u, :h, :d)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("u", uname)
                .addValue("h", encoder.encode(password))
                .addValue("d", dname));
        return new AuthUser(id, uname, dname);
    }

    public AuthUser login(String username, String password) {
        String uname = username.trim().toLowerCase();
        var rows = jdbc.query(
                "select id, password_hash, display_name from users where username = :u",
                Map.of("u", uname),
                (rs, i) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getString("password_hash"),
                        rs.getString("display_name")});
        if (rows.isEmpty()) {
            throw ApiException.unauthorized("invalid credentials");
        }
        Object[] row = rows.get(0);
        if (!encoder.matches(password, (String) row[1])) {
            throw ApiException.unauthorized("invalid credentials");
        }
        return new AuthUser((UUID) row[0], uname, (String) row[2]);
    }
}
