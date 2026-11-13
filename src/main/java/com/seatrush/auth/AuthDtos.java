package com.seatrush.auth;

import com.seatrush.user.Role;
import com.seatrush.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDtos {
    public record RegisterRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            Role role) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record EmailRequest(@NotBlank @Email String email) {}

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {}

    public record ResetPasswordRequest(@NotBlank @Size(max = 200) String token,
                                       @NotBlank @Size(min = 8, max = 72) String password) {}

    public record UserView(Long id, String name, String email, Role role, boolean emailVerified) {
        public static UserView from(User u) {
            return new UserView(u.getId(), u.getName(), u.getEmail(), u.getRole(), u.isEmailVerified());
        }
    }

    public record AuthResponse(String token, UserView user) {}

    public record Message(String message) {}
}
