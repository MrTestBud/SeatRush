package com.seatrush.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * One-time links for "verify your email" and "reset your password".
 * The token itself only ever exists in the email: the database stores its SHA-256, so a leaked
 * database cannot be used to take over accounts. Tokens expire and work once.
 */
@Component
@RequiredArgsConstructor
public class AccountTokens {
    public enum Type { VERIFY_EMAIL, RESET_PASSWORD }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    /** Issues a new token, replacing any unused ones of the same type for this user. */
    public String issue(Long userId, Type type, Duration validFor) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM user_tokens WHERE user_id = ? AND type = ? AND used_at IS NULL", userId, type.name());
        jdbc.update("INSERT INTO user_tokens (user_id, type, token_hash, expires_at) VALUES (?, ?, ?, ?)",
                userId, type.name(), hash(token), Timestamp.from(Instant.now().plus(validFor)));
        return token;
    }

    /** Consumes a token. Returns the user it belongs to, or empty if it is unknown, expired or already used. */
    public Optional<Long> redeem(String token, Type type) {
        // Single statement: two requests with the same token cannot both succeed.
        return jdbc.query("""
                UPDATE user_tokens SET used_at = now()
                WHERE token_hash = ? AND type = ? AND used_at IS NULL AND expires_at > now()
                RETURNING user_id
                """, (rs, i) -> rs.getLong(1), hash(token), type.name()).stream().findFirst();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
