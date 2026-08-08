package dev.ankush.obol.service;

import dev.ankush.obol.repo.BalanceRepository;
import dev.ankush.obol.repo.BalanceRepository.BalanceDrift;
import dev.ankush.obol.repo.OutboxRepository;
import dev.ankush.obol.repo.PostingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Proves the ledger is still telling the truth.
 *
 * <p>Two independent checks, both derived from the postings rather than from
 * anything the write path maintains:
 *
 * <ol>
 *   <li><b>The zero-sum check.</b> Every posting ever written, summed per
 *       currency, must come to zero. If it does not, a transfer was recorded
 *       that was not a transfer.</li>
 *   <li><b>The drift check.</b> Each account's cached balance must equal the
 *       sum of its postings. If it does not, the projection has diverged from
 *       the journal and the cached figure is wrong.</li>
 * </ol>
 *
 * <p>This is what makes the maintained balance safe to keep: the fast path can
 * be trusted precisely because a slow path exists that can contradict it. The
 * concurrency tests assert both checks after hammering the ledger from many
 * threads, and the endpoint is exposed so the same assertion can be made
 * against production.
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final PostingRepository postings;
    private final BalanceRepository balances;
    private final OutboxRepository outbox;
    private final Clock clock;

    public VerificationService(PostingRepository postings,
                               BalanceRepository balances,
                               OutboxRepository outbox,
                               Clock clock) {
        this.postings = postings;
        this.balances = balances;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Report verify() {
        Map<String, Long> sums = postings.sumByCurrency();
        List<BalanceDrift> drift = balances.findDrift();

        Map<String, Long> nonZero = sums.entrySet().stream()
                .filter(e -> e.getValue() != 0)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        boolean healthy = nonZero.isEmpty() && drift.isEmpty();
        Report report = new Report(
                healthy, clock.instant(), postings.count(), sums, nonZero, drift, outbox.unpublishedCount());

        if (!healthy) {
            // There is no recovering from this in code -- if the journal does
            // not balance, something wrote to the database in a way the schema
            // was supposed to forbid, and a human has to look.
            log.error("LEDGER INTEGRITY FAILURE: unbalanced currencies={} driftedAccounts={}",
                    nonZero, drift.size());
        }
        return report;
    }

    /**
     * @param unbalancedCurrencies currencies whose postings do not sum to zero;
     *                             empty in a healthy ledger
     * @param drift                accounts whose stored balance disagrees with
     *                             their postings; empty in a healthy ledger
     * @param unpublishedEvents    outbox backlog -- not an integrity problem,
     *                             but a growing number means the relay is stuck
     */
    public record Report(
            boolean healthy,
            Instant checkedAt,
            long postingCount,
            Map<String, Long> sumByCurrency,
            Map<String, Long> unbalancedCurrencies,
            List<BalanceDrift> drift,
            long unpublishedEvents
    ) {
    }
}
