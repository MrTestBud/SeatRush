package com.seatrush.config;

import com.seatrush.user.Role;

/** The authenticated principal placed in the SecurityContext by JwtAuthFilter. */
public record AuthUser(Long id, String email, Role role) {}
