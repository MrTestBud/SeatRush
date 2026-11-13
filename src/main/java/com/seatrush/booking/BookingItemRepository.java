package com.seatrush.booking;

import com.seatrush.booking.BookingDtos.SeatLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BookingItemRepository extends JpaRepository<BookingItem, Long> {

    List<BookingItem> findByBookingId(Long bookingId);

    @Query("""
            SELECT new com.seatrush.booking.BookingDtos$SeatLine(bi.bookingId, ss.id, s.rowLabel, s.seatNumber, s.tier, bi.price)
            FROM BookingItem bi JOIN ShowSeat ss ON ss.id = bi.showSeatId JOIN ss.seat s
            WHERE bi.bookingId IN :bookingIds
            ORDER BY s.rowLabel, s.seatNumber
            """)
    List<SeatLine> seatLines(@Param("bookingIds") Collection<Long> bookingIds);
}
