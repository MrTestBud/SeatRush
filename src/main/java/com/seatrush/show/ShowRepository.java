package com.seatrush.show;

import com.seatrush.show.ShowDtos.ShowView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ShowRepository extends JpaRepository<Show, Long> {

    String VIEW = """
            SELECT new com.seatrush.show.ShowDtos$ShowView(s.id, s.eventId, e.title, s.venueId, v.name, v.city, s.startsAt, s.basePrice)
            FROM Show s JOIN Event e ON e.id = s.eventId JOIN Venue v ON v.id = s.venueId
            """;

    @Query(VIEW + "WHERE s.eventId = :eventId ORDER BY s.startsAt")
    List<ShowView> viewsForEvent(@Param("eventId") Long eventId);

    @Query(VIEW + "WHERE s.id IN :ids")
    List<ShowView> viewsByIds(@Param("ids") Collection<Long> ids);
}
