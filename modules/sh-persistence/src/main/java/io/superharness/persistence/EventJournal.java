package io.superharness.persistence;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Repository
public class EventJournal {
    private final Sql sql;

    public EventJournal(Sql sql) {
        this.sql = sql;
    }

    /** Caller has authorized the transition or checked the lease in this same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long append(String tenant, UUID run, String type, Object payload) {
        var row =
                sql.first(
                                "SELECT next_seq,attempt FROM agent_runs WHERE tenant_id=? AND id=?"
                                    + " FOR UPDATE",
                                tenant,
                                run)
                        .orElseThrow();
        long previous = ((Number) row.get("next_seq")).longValue();
        long seq = Math.addExact(previous, 1);
        if (sql.update(
                        "UPDATE agent_runs SET next_seq=?,updated_at=UTC_TIMESTAMP(6) WHERE"
                            + " tenant_id=? AND id=? AND next_seq=?",
                        seq,
                        tenant,
                        run,
                        previous)
                != 1) throw new IllegalStateException("Sequence conflict");
        sql.update(
                "INSERT INTO run_events(tenant_id,run_id,seq,attempt,event_type,payload)"
                    + " VALUES(?,?,?,?,?,?)",
                tenant,
                run,
                seq,
                row.get("attempt"),
                type,
                sql.json(payload));
        sql.update("INSERT INTO outbox(tenant_id,run_id,seq) VALUES(?,?,?)", tenant, run, seq);
        return seq;
    }
}
