package com.seatrush.infra;

import com.seatrush.config.AuthUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Throttles the two endpoints worth abusing: credential guessing on /api/auth/* (per IP) and
 * seat-hold spam (per user). Runs after JwtAuthFilter so the user is known.
 * Deliberately NOT a @Component: it must only run inside the security chain, not also as a servlet filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final int authPerMinute;
    private final int holdPerMinute;

    public RateLimitFilter(RateLimiter limiter, int authPerMinute, int holdPerMinute) {
        this.limiter = limiter;
        this.authPerMinute = authPerMinute;
        this.holdPerMinute = holdPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        boolean allowed = true;
        if ("POST".equals(req.getMethod())) {
            String path = req.getRequestURI();
            if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
                allowed = limiter.allow("auth:" + req.getRemoteAddr(), authPerMinute, WINDOW);
            } else if (path.startsWith("/api/shows/") && path.endsWith("/holds")) {
                allowed = limiter.allow("hold:" + caller(req), holdPerMinute, WINDOW);
            }
        }
        if (!allowed) {
            res.setStatus(429);
            res.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            res.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\","
                    + "\"detail\":\"Too many attempts. Wait a minute and try again.\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private String caller(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser u) return "u" + u.id();
        return req.getRemoteAddr();
    }
}
