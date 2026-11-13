package com.seatrush.show;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Demand-based pricing for shows that opt in: once half the house is sold the remaining seats
 * cost 10% more, and 25% more past 80%. Seats already held or booked keep the price they were
 * taken at (it is stored on the booking item), so a price change never alters an existing booking.
 */
@Service
@RequiredArgsConstructor
public class PricingService {
    private final JdbcTemplate jdbc;

    /** Percentage currently added to available seats: 0, 10 or 25. */
    public int surgePercent(Show show) { return surgePercent(show.getId(), show.isDynamicPricing()); }

    public int surgePercent(Long showId, boolean dynamicPricing) {
        if (!dynamicPricing) return 0;
        Double occupancy = jdbc.queryForObject("""
                SELECT COALESCE(count(*) FILTER (WHERE status = 'BOOKED' OR (status = 'HELD' AND held_until > now()))::float
                       / NULLIF(count(*), 0), 0)
                FROM show_seats WHERE show_id = ?
                """, Double.class, showId);
        if (occupancy == null) return 0;
        return occupancy >= 0.8 ? 25 : occupancy >= 0.5 ? 10 : 0;
    }

    public static BigDecimal apply(BigDecimal listPrice, int surgePercent) {
        if (surgePercent == 0) return listPrice;
        return listPrice.multiply(BigDecimal.valueOf(100 + surgePercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
