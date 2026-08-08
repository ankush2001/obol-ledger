package dev.ankush.obol.scheduler;

import dev.ankush.obol.domain.Transfer;
import dev.ankush.obol.error.LedgerException;
import dev.ankush.obol.repo.TransferRepository;
import dev.ankush.obol.service.IdempotencyService;
import dev.ankush.obol.service.TransferService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * The background work that keeps the ledger tidy.
 */
@Component
public class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);

    private final TransferRepository transfers;
    private final TransferService transferService;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final int expiryBatchSize;
    private final Counter expired;

    public MaintenanceJobs(TransferRepository transfers,
                           TransferService transferService,
                           IdempotencyService idempotency,
                           MeterRegistry meters,
                           Clock clock,
                           @Value("${obol.expiry.batch-size:200}") int expiryBatchSize) {
        this.transfers = transfers;
        this.transferService = transferService;
        this.idempotency = idempotency;
        this.clock = clock;
        this.expiryBatchSize = expiryBatchSize;
        this.expired = Counter.builder("obol.transfers.expired").register(meters);
    }

    /**
     * Releases authorisations nobody captured in time.
     *
     * <p>Without this, a pending transfer holds its reservation forever and the
     * payer's money is quietly unavailable -- the ledger would be arithmetically
     * correct and practically broken.
     *
     * <p>Each transfer is expired in its own transaction rather than the batch
     * sharing one. A single transfer that cannot be released must not prevent
     * the other 199 from being released, and a batch-wide rollback would put
     * every one of them back.
     */
    @Scheduled(fixedDelayString = "${obol.expiry.poll-interval:10000}")
    public void expirePendingTransfers() {
        List<Transfer> due = transfers.findExpired(clock.instant(), expiryBatchSize);
        if (due.isEmpty()) {
            return;
        }

        int released = 0;
        for (Transfer transfer : due) {
            try {
                transferService.expire(transfer.id());
                released++;
            } catch (LedgerException.InvalidTransferState e) {
                // Captured or voided between the scan and now. Entirely
                // expected under load, and not worth a warning.
                log.debug("transfer {} settled before it could expire", transfer.id());
            } catch (RuntimeException e) {
                log.warn("could not expire transfer {}", transfer.id(), e);
            }
        }

        expired.increment(released);
        log.info("expired {} of {} due pending transfers", released, due.size());
    }

    /**
     * Drops idempotency keys past their retention window. They are only useful
     * for as long as a client might still retry.
     */
    @Scheduled(fixedDelayString = "${obol.idempotency.purge-interval:3600000}")
    public void purgeIdempotencyKeys() {
        int purged = idempotency.purgeExpired();
        if (purged > 0) {
            log.info("purged {} expired idempotency keys", purged);
        }
    }
}
