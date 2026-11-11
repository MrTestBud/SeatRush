package com.seatrush.auth;

import com.seatrush.auth.AuthDtos.*;
import com.seatrush.common.ConflictException;
import com.seatrush.config.JwtService;
import com.seatrush.user.Role;
import com.seatrush.user.User;
import com.seatrush.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

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
        return new AuthResponse(jwt.generate(u), UserView.from(u));
    }

    public AuthResponse login(LoginRequest r) {
        User u = users.findByEmail(r.email().trim().toLowerCase())
                .filter(x -> encoder.matches(r.password(), x.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("bad credentials"));
        return new AuthResponse(jwt.generate(u), UserView.from(u));
    }
}
