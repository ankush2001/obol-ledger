package dev.ankush.obol.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the ledger tells the outside world.
 *
 * <p>Deliberately a flat, self-contained description rather than a reference
 * the consumer has to call back for. A reconciliation service reading these
 * must be able to work from the event alone -- if it has to re-read the ledger
 * to interpret an event, it is no longer reconciling an independent record.
 */
public record LedgerEvent(
        String eventType,
        UUID transferId,
        String externalId,
        String state,
        String currency,
        long amountMinor,
        String description,
        Instant occurredAt,
        List<EventLeg> legs
) {

    public record EventLeg(String accountCode, String direction, long amountMinor) {
    }

    public static final String TRANSFER_POSTED = "transfer.posted";
    public static final String TRANSFER_PENDING = "transfer.pending";
    public static final String TRANSFER_VOIDED = "transfer.voided";
    public static final String TRANSFER_EXPIRED = "transfer.expired";
}
