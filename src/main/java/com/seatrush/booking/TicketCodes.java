package com.seatrush.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.OptionalLong;

/**
 * E-ticket codes (the content of the QR code): {@code SR-<bookingId>-<signature>}.
 * The signature is an HMAC of the booking id, so a ticket cannot be forged or guessed by
 * changing the number, and verifying one needs no database lookup.
 */
@Component
public class TicketCodes {
    private final byte[] secret;

    public TicketCodes(@Value("${app.ticket.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String codeFor(Long bookingId) { return "SR-" + bookingId + "-" + signature(bookingId); }

    /** @return the booking id if the code is genuine. */
    public OptionalLong verify(String code) {
        String[] parts = code == null ? new String[0] : code.trim().toUpperCase().split("-");
        if (parts.length != 3 || !parts[0].equals("SR")) return OptionalLong.empty();
        long id;
        try {
            id = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
        boolean genuine = MessageDigest.isEqual(signature(id).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8));
        return genuine ? OptionalLong.of(id) : OptionalLong.empty();
    }

    private String signature(long bookingId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(("ticket:" + bookingId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest, 0, 8);   // 16 hex chars: short enough to type
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign ticket", e);
        }
    }
}
