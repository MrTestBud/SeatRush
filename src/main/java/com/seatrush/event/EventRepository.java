package com.seatrush.event;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * PostgreSQL full-text search, ranked by relevance; served by the GIN index on search_vector.
     * An empty :category means "any category".
     */
    @Query(value = """
            SELECT * FROM events
            WHERE search_vector @@ plainto_tsquery('english', :q)
              AND (:category = '' OR upper(category) = upper(:category))
            ORDER BY ts_rank(search_vector, plainto_tsquery('english', :q)) DESC, id DESC
            """,
            countQuery = """
                    SELECT count(*) FROM events
                    WHERE search_vector @@ plainto_tsquery('english', :q)
                      AND (:category = '' OR upper(category) = upper(:category))
                    """,
            nativeQuery = true)
    Page<Event> search(@Param("q") String q, @Param("category") String category, Pageable pageable);

    Page<Event> findByCategoryIgnoreCase(String category, Pageable pageable);

    List<Event> findByOrganizerIdOrderByIdDesc(Long organizerId);

    @Query("SELECT DISTINCT upper(e.category) FROM Event e ORDER BY 1")
    List<String> categories();
}
