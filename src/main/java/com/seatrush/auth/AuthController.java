package com.seatrush.auth;

import com.seatrush.auth.AuthDtos.*;
import com.seatrush.common.NotFoundException;
import com.seatrush.config.AuthUser;
import com.seatrush.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService auth;
    private final UserRepository users;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest r) { return auth.register(r); }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest r) { return auth.login(r); }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthUser me) {
        return users.findById(me.id()).map(UserView::from).orElseThrow(() -> new NotFoundException("User not found"));
    }

    @PostMapping("/verify-email")
    public UserView verifyEmail(@Valid @RequestBody TokenRequest r) { return auth.verifyEmail(r.token()); }

    @PostMapping("/resend-verification")
    public Message resendVerification(@AuthenticationPrincipal AuthUser me) {
        auth.resendVerification(me.id());
        return new Message("If your email is not verified yet, a new link is on its way.");
    }

    @PostMapping("/forgot-password")
    public Message forgotPassword(@Valid @RequestBody EmailRequest r) {
        auth.forgotPassword(r.email());
        return new Message("If that address has an account, a reset link is on its way.");
    }

    @PostMapping("/reset-password")
    public Message resetPassword(@Valid @RequestBody ResetPasswordRequest r) {
        auth.resetPassword(r.token(), r.password());
        return new Message("Your password has been changed. You can log in now.");
    }
}
