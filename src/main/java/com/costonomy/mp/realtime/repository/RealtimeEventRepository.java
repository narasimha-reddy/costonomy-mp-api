package com.costonomy.mp.realtime.repository;

import com.costonomy.mp.realtime.domain.RealtimeEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RealtimeEventRepository extends JpaRepository<RealtimeEvent, Long> {

    /**
     * Everything on these channels after a cursor, oldest first.
     *
     * <p>The one query every transport makes. Ordered by id rather than by
     * {@code occurredAt}: the cursor has to be total and monotonic, and two events
     * in the same millisecond would otherwise be able to skip each other.
     */
    @Query("""
            select e from RealtimeEvent e
             where e.channel in :channels
               and e.id > :since
             order by e.id asc
            """)
    List<RealtimeEvent> since(@Param("channels") List<String> channels,
                              @Param("since") Long since,
                              Pageable pageable);

    /** The newest id on these channels, so a fresh client can start at "now". */
    @Query("""
            select coalesce(max(e.id), 0) from RealtimeEvent e
             where e.channel in :channels
            """)
    Long latestCursor(@Param("channels") List<String> channels);

    long deleteByCreatedAtBefore(Instant before);
}
