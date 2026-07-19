package com.ephemeral.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads a Bearer token, and if valid stashes the {@link AuthUser} as a request
 * attribute. Rejects unauthenticated calls to protected /api/ routes with 401.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final AuthService auth;

    public AuthFilter(JwtService jwt, AuthService auth) {
        this.jwt = jwt;
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthUser user = jwt.parse(header.substring(7));
                // ghost tokens (deleted account / reset DB) must 401, not FK-500
                if (auth.userExists(user.id())) {
                    request.setAttribute(CurrentUserArgumentResolver.ATTR, user);
                }
            } catch (Exception ignored) {
                // fall through as unauthenticated
            }
        }

        if (requiresAuth(request.getRequestURI())
                && request.getAttribute(CurrentUserArgumentResolver.ATTR) == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean requiresAuth(String path) {
        if (!path.startsWith("/api/")) {
            return false; // static assets and /ws handle themselves
        }
        return !path.startsWith("/api/auth/")
                && !path.equals("/api/health")
                && !path.startsWith("/api/files/") // file GETs are public (unguessable ids)
                && !path.startsWith("/api/livekit/"); // livekit webhook (verified by signature)
    }
}
