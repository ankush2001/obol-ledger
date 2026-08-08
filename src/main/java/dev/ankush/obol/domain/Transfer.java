package dev.ankush.obol.domain;

import dev.ankush.obol.domain.Enums.Direction;
import dev.ankush.obol.domain.Enums.TransferState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A transfer and its legs.
 *
 * <p>Modelled as N legs rather than a debit/credit pair so that the ordinary
 * shape of a real payment -- charge the payer 1000, pay the merchant 971,
 * book 29 to fee revenue -- is one atomic transfer rather than two that can
 * half-fail.
 */
public record Transfer(
        UUID id,
        String externalId,
        TransferState state,
        String currency,
        long amountMinor,
        String description,
        Instant pendingExpiresAt,
        Instant createdAt,
        Instant postedAt,
        Instant voidedAt,
        List<Leg> legs
) {

    /** One side of a transfer, before it becomes an immutable posting. */
    public record Leg(UUID accountId, String accountCode, String currency, Direction direction, long amountMinor) {

        /** Debit positive, credit negative -- the form actually stored. */
        public long signedMinor() {
            return direction.signum() * amountMinor;
        }
    }
}
