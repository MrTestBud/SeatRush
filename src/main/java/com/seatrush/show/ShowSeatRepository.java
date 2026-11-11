package com.seatrush.show;

import com.seatrush.show.ShowDtos.SeatRow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

    @Query("""
            SELECT new com.seatrush.show.ShowDtos$SeatRow(ss.id, s.rowLabel, s.seatNumber, s.tier, ss.status, ss.price, ss.heldUntil)
            FROM ShowSeat ss JOIN ss.seat s
            WHERE ss.showId = :showId
            ORDER BY s.rowLabel, s.seatNumber
            """)
    List<SeatRow> seatMap(@Param("showId") Long showId);

    /**
     * PESSIMISTIC strategy: SELECT ... FOR UPDATE. ORDER BY id means every transaction takes its
     * row locks in the same order, so two users picking overlapping seats cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ss FROM ShowSeat ss WHERE ss.showId = :showId AND ss.id IN :ids ORDER BY ss.id")
    List<ShowSeat> lockForHold(@Param("showId") Long showId, @Param("ids") Collection<Long> ids);

    /** OPTIMISTIC strategy: plain read; the @Version check at UPDATE time decides the winner. */
    @Query("SELECT ss FROM ShowSeat ss WHERE ss.showId = :showId AND ss.id IN :ids ORDER BY ss.id")
    List<ShowSeat> findForHold(@Param("showId") Long showId, @Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ss FROM ShowSeat ss WHERE ss.id IN :ids ORDER BY ss.id")
    List<ShowSeat> lockByIds(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ss FROM ShowSeat ss WHERE ss.bookingId = :bookingId ORDER BY ss.id")
    List<ShowSeat> lockByBookingId(@Param("bookingId") Long bookingId);
}
