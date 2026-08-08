package dev.ankush.obol.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ankush.obol.domain.Transfer;
import dev.ankush.obol.outbox.LedgerEvent;
import dev.ankush.obol.repo.OutboxRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Writes ledger events into the transactional outbox.
 *
 * <p>No HTTP call happens here. The event is a row, inserted in the caller's
 * transaction, and a separate relay delivers it later. That indirection is the
 * point: publishing inside the transaction would either block the commit on a
 * remote service or, if it were done just after, open a window where the money
 * moved and the announcement was lost.
 */
@Service
public class OutboxService {

    private static final String AGGREGATE_TYPE = "transfer";

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public OutboxService(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public void append(Transfer transfer, String eventType, Instant occurredAt) {
        LedgerEvent event = new LedgerEvent(
                eventType,
                transfer.id(),
                transfer.externalId(),
                transfer.state().name(),
                transfer.currency(),
                transfer.amountMinor(),
                transfer.description(),
                occurredAt,
                transfer.legs().stream()
                        .map(l -> new LedgerEvent.EventLeg(
                                l.accountCode(), l.direction().name(), l.amountMinor()))
                        .toList());

        outbox.append(AGGREGATE_TYPE, transfer.id().toString(), eventType, serialise(event));
    }

    private String serialise(LedgerEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Unreachable for a record of primitives, but if it ever happened
            // the transfer must not commit half-announced.
            throw new IllegalStateException("could not serialise ledger event", e);
        }
    }
}
