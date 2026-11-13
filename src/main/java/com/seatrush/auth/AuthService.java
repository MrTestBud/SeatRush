package com.seatrush.auth;

import com.seatrush.auth.AccountTokens.Type;
import com.seatrush.auth.AuthDtos.*;
import com.seatrush.common.BadRequestException;
import com.seatrush.common.ConflictException;
import com.seatrush.common.NotFoundException;
import com.seatrush.config.JwtService;
import com.seatrush.notification.OutboxMessage;
import com.seatrush.notification.OutboxRepository;
import com.seatrush.user.Role;
import com.seatrush.user.User;
import com.seatrush.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class AuthService {
    private static final Duration VERIFY_VALID = Duration.ofHours(24);
    private static final Duration RESET_VALID = Duration.ofMinutes(30);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AccountTokens tokens;
    private final OutboxRepository outbox;
    private final String frontendUrl;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, AccountTokens tokens,
                       OutboxRepository outbox, @Value("${app.frontend-url}") String frontendUrl) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.tokens = tokens;
        this.outbox = outbox;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    @Transactional
    public AuthResponse register(RegisterRequest r) {
        String email = r.email().trim().toLowerCase();
        if (users.existsByEmail(email)) throw new ConflictException("Email already registered");

        User u = new User();
        u.setName(r.name().trim());
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(r.password()));
        // Self-service signup may only create USER or ORGANIZER accounts; ADMIN is never self-assigned.
        u.setRole(r.role() == Role.ORGANIZER ? Role.ORGANIZER : Role.USER);
        users.save(u);
        sendVerification(u);
        return new AuthResponse(jwt.generate(u), UserView.from(u));
    }

    public AuthResponse login(LoginRequest r) {
        User u = users.findByEmail(r.email().trim().toLowerCase())
                .filter(x -> encoder.matches(r.password(), x.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("bad credentials"));
        return new AuthResponse(jwt.generate(u), UserView.from(u));
    }

    // ------------------------------------------------------------------ email verification

    @Transactional
    public void resendVerification(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (!u.isEmailVerified()) sendVerification(u);
    }

    @Transactional
    public UserView verifyEmail(String token) {
        Long userId = tokens.redeem(token, Type.VERIFY_EMAIL)
                .orElseThrow(() -> new BadRequestException("This verification link is invalid or has expired. Ask for a new one."));
        User u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        u.setEmailVerified(true);
        return UserView.from(u);
    }

    private void sendVerification(User u) {
        String link = frontendUrl + "/verify-email?token=" + tokens.issue(u.getId(), Type.VERIFY_EMAIL, VERIFY_VALID);
        outbox.save(new OutboxMessage(u.getEmail(), "Confirm your SeatRush email address",
                "Hi " + u.getName() + ",\n\nConfirm this is your email address by opening this link (valid for 24 hours):\n"
                        + link + "\n\nIf you did not create a SeatRush account, ignore this email."));
    }

    // ------------------------------------------------------------------ password reset

    /**
     * Always succeeds from the caller's point of view, whether or not the address has an account,
     * so this endpoint cannot be used to find out who is registered.
     */
    @Transactional
    public void forgotPassword(String rawEmail) {
        users.findByEmail(rawEmail.trim().toLowerCase()).ifPresent(u -> {
            String link = frontendUrl + "/reset-password?token=" + tokens.issue(u.getId(), Type.RESET_PASSWORD, RESET_VALID);
            outbox.save(new OutboxMessage(u.getEmail(), "Reset your SeatRush password",
                    "Hi " + u.getName() + ",\n\nChoose a new password by opening this link (valid for 30 minutes, works once):\n"
                            + link + "\n\nIf you did not ask for this, ignore this email. Your password has not changed."));
        });
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        Long userId = tokens.redeem(token, Type.RESET_PASSWORD)
                .orElseThrow(() -> new BadRequestException("This reset link is invalid or has expired. Ask for a new one."));
        User u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        u.setPasswordHash(encoder.encode(newPassword));
        u.setEmailVerified(true);   // they just proved they can read this inbox
    }
}
