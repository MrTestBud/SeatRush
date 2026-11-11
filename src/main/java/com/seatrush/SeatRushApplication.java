package com.seatrush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// Auth is JWT-only (JwtAuthFilter), so Spring's default in-memory user with a generated password is switched off.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class SeatRushApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeatRushApplication.class, args);
    }
}
