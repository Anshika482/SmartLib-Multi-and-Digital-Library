package com.smartlib.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class AuditWriter {

    private final AuditLogRepository repository;

    AuditWriter(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void writeInCurrentTransaction(AuditLog log) {
        repository.save(log);
    }

    /** Security events must persist even when the surrounding business transaction rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeIndependently(AuditLog log) {
        repository.save(log);
    }
}
