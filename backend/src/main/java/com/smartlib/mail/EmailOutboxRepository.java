package com.smartlib.mail;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {

    @Query("select e from EmailOutbox e where e.status = 'PENDING' and e.nextAttemptAt <= :now order by e.id")
    List<EmailOutbox> findDue(Instant now, Pageable pageable);
}
