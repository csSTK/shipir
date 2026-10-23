package dev.bethge.shipir.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final Clock clock;

    AuditService(AuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Own transaction, so an entry survives a rollback of the audited action. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditRecord record) {
        repository.save(new AuditLogEntry(Instant.now(clock), record));
    }
}
