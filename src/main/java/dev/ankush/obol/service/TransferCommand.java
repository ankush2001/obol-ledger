package dev.ankush.obol.service;

import dev.ankush.obol.domain.Enums.Direction;

import java.time.Duration;
import java.util.List;

/**
 * A validated instruction to move money, independent of how it arrived.
 *
 * @param pending  when true the transfer is authorised but not settled: funds
 *                 are reserved and no postings are written until capture.
 * @param pendingTtl how long the authorisation holds before the sweeper
 *                 releases it. Ignored unless {@code pending}.
 */
public record TransferCommand(
        String externalId,
        String currency,
        String description,
        List<LegCommand> legs,
        boolean pending,
        Duration pendingTtl
) {

    public record LegCommand(String accountCode, Direction direction, long amountMinor) {
        public long signedMinor() {
            return direction.signum() * amountMinor;
        }
    }
}
