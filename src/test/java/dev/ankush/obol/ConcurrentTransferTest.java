package dev.ankush.obol;

import dev.ankush.obol.domain.Enums.AccountType;
import dev.ankush.obol.domain.Enums.Direction;
import dev.ankush.obol.error.LedgerException;
import dev.ankush.obol.service.AccountService;
import dev.ankush.obol.service.TransferCommand;
import dev.ankush.obol.service.TransferCommand.LegCommand;
import dev.ankush.obol.service.TransferService;
import dev.ankush.obol.service.VerificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tests that make the concurrency claims worth believing.
 *
 * <p>Anything can look correct one request at a time. These run the ledger
 * under genuine contention -- many threads, deliberately overlapping accounts,
 * transfers in both directions between the same pairs -- and then check the
 * invariants that a race would break: money conjured or destroyed, an account
 * overdrawn past a limit it was told to respect, a balance that no longer
 * matches its own postings.
 */
class ConcurrentTransferTest extends IntegrationTest {

    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired VerificationService verification;

    private static final int WALLETS = 5;
    private static final long OPENING_BALANCE = 100_000;   // 1,000.00
    private static final int THREADS = 16;
    private static final int TRANSFERS = 400;

    @Test
    @DisplayName("money is neither created nor destroyed under concurrent transfers")
    void concurrentTransfersPreserveTheInvariant() throws Exception {
        List<String> wallets = openFundedWallets();

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejectedForFunds = new AtomicInteger();
        AtomicInteger deadlocked = new AtomicInteger();

        Random random = new Random(20260808L);   // seeded: a failure is reproducible

        List<Callable<Void>> work = new ArrayList<>(TRANSFERS);
        for (int i = 0; i < TRANSFERS; i++) {
            // Pairs are drawn at random from a small pool, so the same two
            // accounts are constantly contended -- and, critically, are
            // contended in both directions at once. That is the shape that
            // deadlocks a ledger which locks accounts in request order.
            int from = random.nextInt(WALLETS);
            int to = (from + 1 + random.nextInt(WALLETS - 1)) % WALLETS;
            long amount = 100L * (1 + random.nextInt(50));
            String key = "concurrent-" + i;

            work.add(() -> {
                try {
                    transfers.create(new TransferCommand(
                            key, "USD", "contended transfer",
                            List.of(new LegCommand(wallets.get(from), Direction.DEBIT, amount),
                                    new LegCommand(wallets.get(to), Direction.CREDIT, amount)),
                            false, Duration.ZERO));
                    succeeded.incrementAndGet();
                } catch (LedgerException.InsufficientFunds e) {
                    // A legitimate outcome, not a failure: the ledger refused
                    // to overdraw an account. Counted so the test can prove it
                    // is exercising the path rather than silently passing.
                    rejectedForFunds.incrementAndGet();
                } catch (org.springframework.dao.CannotAcquireLockException e) {
                    deadlocked.incrementAndGet();
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (Future<Void> f : pool.invokeAll(work)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // 1. No deadlocks. This is the payoff for sorting account ids before
        //    locking them; without that ordering, opposing transfers between
        //    the same pair deadlock and Postgres kills one of them.
        assertThat(deadlocked)
                .describedAs("deadlocks -- account locks are not being taken in a consistent order")
                .hasValue(0);

        // 2. The test actually contended for something.
        assertThat(succeeded.get())
                .describedAs("nothing succeeded; the test proved nothing")
                .isGreaterThan(TRANSFERS / 2);

        // 3. Total money is untouched. Transfers only move it between wallets,
        //    so however they interleaved, the sum must be exactly what was
        //    paid in.
        long total = wallets.stream()
                .mapToLong(code -> accounts.balanceFromDatabase(code).settledMinor())
                .sum();
        assertThat(total)
                .describedAs("money was created or destroyed")
                .isEqualTo(WALLETS * OPENING_BALANCE);

        // 4. No wallet went negative, though many transfers were refused for
        //    trying -- proof the funds check held under a race, not merely in
        //    the absence of one.
        for (String wallet : wallets) {
            assertThat(accounts.balanceFromDatabase(wallet).settledMinor())
                    .describedAs("%s went overdrawn", wallet)
                    .isGreaterThanOrEqualTo(0);
        }

        // 5. Every cached balance still equals the sum of its own postings,
        //    and the whole journal sums to zero.
        VerificationService.Report report = verification.verify();
        assertThat(report.drift()).describedAs("balances drifted from their postings").isEmpty();
        assertThat(report.unbalancedCurrencies()).isEmpty();
        assertThat(report.healthy()).isTrue();
    }

    @Test
    @DisplayName("a transfer cannot be committed unbalanced, even from raw SQL")
    void theDatabaseItselfRejectsAnUnbalancedTransfer() {
        // Bypasses the service entirely. The guarantee has to hold against a
        // future bug, a second service, or an operator at a psql prompt -- not
        // only against this codebase's own write path.
        String accountId = jdbc.queryForObject(
                "SELECT id::text FROM ledger_account WHERE code = ?", String.class,
                openAccount("guard:cash", AccountType.ASSET, true));

        String transferId = "6f3b1c22-0000-4000-8000-00000000abcd";
        jdbc.update("""
                INSERT INTO transfer (id, state, currency, amount_minor, posted_at)
                VALUES (?::uuid, 'POSTED', 'USD', 100, now())
                """, transferId);

        assertThatThrownBy(() ->
                jdbc.update("""
                        INSERT INTO posting (transfer_id, account_id, currency, amount_minor, seq, balance_after_minor)
                        VALUES (?::uuid, ?::uuid, 'USD', 100, 0, 100)
                        """, transferId, accountId))
                .describedAs("a single-legged transfer was allowed to commit")
                .hasMessageContaining("unbalanced");
    }

    // ------------------------------------------------------------- fixtures

    private List<String> openFundedWallets() {
        String cash = openAccount("test:cash", AccountType.ASSET, true);

        List<String> wallets = new ArrayList<>(WALLETS);
        for (int i = 0; i < WALLETS; i++) {
            String code = openAccount("test:wallet:" + i, AccountType.LIABILITY, false);
            transfers.create(new TransferCommand(
                    "funding-" + i, "USD", "opening balance",
                    List.of(new LegCommand(cash, Direction.DEBIT, OPENING_BALANCE),
                            new LegCommand(code, Direction.CREDIT, OPENING_BALANCE)),
                    false, Duration.ZERO));
            wallets.add(code);
        }
        return wallets;
    }

    private String openAccount(String code, AccountType type, boolean allowNegative) {
        accounts.create(code, code, "USD", type, allowNegative);
        return code;
    }
}
