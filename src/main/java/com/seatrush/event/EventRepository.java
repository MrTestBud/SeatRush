package com.seatrush.event;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    /** PostgreSQL full-text search, ranked by relevance; served by the GIN index on search_vector. */
    @Query(value = """
            SELECT * FROM events
            WHERE search_vector @@ plainto_tsquery('english', :q)
            ORDER BY ts_rank(search_vector, plainto_tsquery('english', :q)) DESC, id DESC
            """,
            countQuery = "SELECT count(*) FROM events WHERE search_vector @@ plainto_tsquery('english', :q)",
            nativeQuery = true)
    Page<Event> search(@Param("q") String q, Pageable pageable);

    java.util.List<Event> findByOrganizerIdOrderByIdDesc(Long organizerId);
}
