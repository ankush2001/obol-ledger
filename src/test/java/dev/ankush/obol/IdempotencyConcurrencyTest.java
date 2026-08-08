package dev.ankush.obol;

import dev.ankush.obol.api.TransferDtos.CreateTransferRequest;
import dev.ankush.obol.api.TransferDtos.LegRequest;
import dev.ankush.obol.api.TransferDtos.TransferResponse;
import dev.ankush.obol.domain.Enums.AccountType;
import dev.ankush.obol.domain.Enums.Direction;
import dev.ankush.obol.error.LedgerException;
import dev.ankush.obol.service.AccountService;
import dev.ankush.obol.service.IdempotencyService;
import dev.ankush.obol.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Idempotency under a real race.
 *
 * <p>The interesting case is not a client retrying politely after a timeout --
 * it is twenty copies of the same request arriving at once, which is what a
 * retrying client behind a load balancer actually produces. Exactly one of
 * them may move money.
 */
class IdempotencyConcurrencyTest extends IntegrationTest {

    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired IdempotencyService idempotency;

    private static final int ATTEMPTS = 20;

    @Test
    @DisplayName("twenty simultaneous retries of one payment move money exactly once")
    void simultaneousRetriesExecuteOnce() throws Exception {
        accounts.create("race:cash", "cash", "USD", AccountType.ASSET, true);
        accounts.create("race:alice", "alice", "USD", AccountType.LIABILITY, false);

        CreateTransferRequest request = new CreateTransferRequest(
                "race-payment", "USD", "one payment, many retries",
                List.of(new LegRequest("race:cash", Direction.DEBIT, 5_000),
                        new LegRequest("race:alice", Direction.CREDIT, 5_000)),
                false, null);

        // Every thread waits at the barrier and is released together, so the
        // requests genuinely overlap instead of merely being submitted quickly.
        CyclicBarrier startTogether = new CyclicBarrier(ATTEMPTS);
        Set<String> transferIds = ConcurrentHashMap.newKeySet();
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger toldToRetry = new AtomicInteger();

        List<Callable<Void>> attempts = new java.util.ArrayList<>();
        for (int i = 0; i < ATTEMPTS; i++) {
            attempts.add(() -> {
                startTogether.await();
                try {
                    var outcome = idempotency.execute("same-key", request, TransferResponse.class,
                            () -> TransferResponse.from(transfers.create(request.toCommand())));

                    transferIds.add(outcome.value().id().toString());
                    if (outcome.replayed()) {
                        replayed.incrementAndGet();
                    } else {
                        executed.incrementAndGet();
                    }
                } catch (LedgerException.RequestInFlight e) {
                    // The honest answer while the winner is still committing:
                    // the response does not exist yet, so it cannot be
                    // replayed, and re-running the work would double-pay.
                    toldToRetry.incrementAndGet();
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
        try {
            for (Future<Void> f : pool.invokeAll(attempts)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(executed).describedAs("more than one attempt actually moved money").hasValue(1);
        assertThat(transferIds).describedAs("attempts disagreed about which transfer this was").hasSize(1);
        assertThat(replayed.get() + toldToRetry.get()).isEqualTo(ATTEMPTS - 1);

        // The only thing that ultimately matters: one payment, not twenty.
        assertThat(accounts.balanceFromDatabase("race:alice").settledMinor()).isEqualTo(5_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("reusing a key for a different payment is refused, not replayed")
    void reusingAKeyForDifferentContentIsRefused() {
        accounts.create("reuse:cash", "cash", "USD", AccountType.ASSET, true);
        accounts.create("reuse:alice", "alice", "USD", AccountType.LIABILITY, false);

        CreateTransferRequest first = new CreateTransferRequest(
                null, "USD", null,
                List.of(new LegRequest("reuse:cash", Direction.DEBIT, 1_000),
                        new LegRequest("reuse:alice", Direction.CREDIT, 1_000)),
                false, null);

        idempotency.execute("shared-key", first, TransferResponse.class,
                () -> TransferResponse.from(transfers.create(first.toCommand())));

        CreateTransferRequest different = new CreateTransferRequest(
                null, "USD", null,
                List.of(new LegRequest("reuse:cash", Direction.DEBIT, 999_999),
                        new LegRequest("reuse:alice", Direction.CREDIT, 999_999)),
                false, null);

        assertThatThrownBy(() -> idempotency.execute("shared-key", different, TransferResponse.class,
                () -> TransferResponse.from(transfers.create(different.toCommand()))))
                .isInstanceOf(LedgerException.IdempotencyKeyReused.class);

        // Neither executed nor replayed: replaying the first response would
        // have told the caller their 9,999.99 payment succeeded.
        assertThat(accounts.balanceFromDatabase("reuse:alice").settledMinor()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("a failed request releases its key so an honest retry can succeed")
    void failedWorkReleasesTheKey() {
        accounts.create("release:cash", "cash", "USD", AccountType.ASSET, true);
        accounts.create("release:alice", "alice", "USD", AccountType.LIABILITY, false);

        CreateTransferRequest request = new CreateTransferRequest(
                null, "USD", null,
                List.of(new LegRequest("release:cash", Direction.DEBIT, 2_000),
                        new LegRequest("release:alice", Direction.CREDIT, 2_000)),
                false, null);

        assertThatThrownBy(() -> idempotency.execute("retryable", request, TransferResponse.class,
                () -> {
                    throw new IllegalStateException("the database fell over");
                }))
                .isInstanceOf(IllegalStateException.class);

        // Without the compensating release this would be a permanent 409: the
        // key would sit IN_FLIGHT forever describing work that never happened.
        var outcome = idempotency.execute("retryable", request, TransferResponse.class,
                () -> TransferResponse.from(transfers.create(request.toCommand())));

        assertThat(outcome.replayed()).isFalse();
        assertThat(accounts.balanceFromDatabase("release:alice").settledMinor()).isEqualTo(2_000);
    }
}
