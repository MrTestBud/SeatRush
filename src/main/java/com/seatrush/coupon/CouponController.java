package com.seatrush.coupon;

import com.seatrush.common.ConflictException;
import com.seatrush.common.NotFoundException;
import com.seatrush.config.AuthUser;
import com.seatrush.event.EventService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** Organizers manage discount codes for their own events. Buyers apply them on the booking (see BookingController). */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
@RequiredArgsConstructor
public class CouponController {
    private final CouponRepository coupons;
    private final EventService events;

    public record CouponRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,40}", message = "use 3-40 letters, digits, - or _") String code,
            @NotNull @Min(1) @Max(90) Integer percentOff,
            @NotNull @Min(1) @Max(1000000) Integer maxUses,
            @Future Instant expiresAt) {}

    public record CouponView(Long id, String code, int percentOff, int maxUses, int usedCount, Instant expiresAt) {
        static CouponView from(Coupon c) {
            return new CouponView(c.getId(), c.getCode(), c.getPercentOff(), c.getMaxUses(), c.getUsedCount(), c.getExpiresAt());
        }
    }

    @PostMapping("/events/{eventId}/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CouponView create(@AuthenticationPrincipal AuthUser me, @PathVariable Long eventId,
                             @Valid @RequestBody CouponRequest r) {
        events.owned(me, eventId);
        String code = r.code().trim().toUpperCase();
        if (coupons.findByEventIdAndCode(eventId, code).isPresent())
            throw new ConflictException("This event already has a coupon with the code " + code);
        return CouponView.from(coupons.save(new Coupon(eventId, code, r.percentOff(), r.maxUses(), r.expiresAt())));
    }

    @GetMapping("/events/{eventId}/coupons")
    @Transactional(readOnly = true)
    public List<CouponView> list(@AuthenticationPrincipal AuthUser me, @PathVariable Long eventId) {
        events.owned(me, eventId);
        return coupons.findByEventIdOrderByIdDesc(eventId).stream().map(CouponView::from).toList();
    }

    @DeleteMapping("/coupons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        Coupon c = coupons.findById(id).orElseThrow(() -> new NotFoundException("Coupon " + id + " not found"));
        events.owned(me, c.getEventId());
        coupons.delete(c);   // bookings that already used it keep their discount (coupon_id is set to NULL)
    }
}
