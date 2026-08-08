package dev.ankush.obol.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A single immutable entry in the journal.
 *
 * @param amountMinor        signed: debit positive, credit negative
 * @param balanceAfterMinor  the account's signed settled balance immediately
 *                           after this entry was applied, captured under the
 *                           same row lock that updated it. Lets a statement
 *                           be rendered without re-summing the account's
 *                           entire history, and makes any later drift
 *                           pinpointable to a specific row.
 */
public record Posting(
        long id,
        UUID transferId,
        UUID accountId,
        String currency,
        long amountMinor,
        int seq,
        long balanceAfterMinor,
        Instant createdAt
) {
    public boolean isDebit() {
        return amountMinor > 0;
    }
}
