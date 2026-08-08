package dev.ankush.obol;

import dev.ankush.obol.domain.Enums.AccountType;
import dev.ankush.obol.domain.Enums.Direction;
import dev.ankush.obol.domain.Enums.TransferState;
import dev.ankush.obol.domain.Transfer;
import dev.ankush.obol.error.LedgerException;
import dev.ankush.obol.scheduler.MaintenanceJobs;
import dev.ankush.obol.service.AccountService;
import dev.ankush.obol.service.TransferCommand;
import dev.ankush.obol.service.TransferCommand.LegCommand;
import dev.ankush.obol.service.TransferService;
import dev.ankush.obol.service.VerificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What happens to an authorisation nobody captures.
 *
 * <p>This is the failure mode that quietly costs customers money: a hold is
 * placed, the merchant never captures, and the funds stay unavailable
 * indefinitely. The ledger's books would balance perfectly the whole time.
 */
@Import(PendingExpiryTest.FrozenClock.class)
class PendingExpiryTest extends IntegrationTest {

    @TestConfiguration
    static class FrozenClock {
        // Named testClock, not clock: an identically named bean would collide
        // with LedgerConfig#clock, and Spring Boot rejects bean definition
        // overriding by default rather than silently picking a winner. A
        // different name plus @Primary expresses the intent properly -- both
        // beans exist, this one is injected.
        @Bean
        @Primary
        Clock testClock() {
            return new MutableClock(Instant.parse("2026-08-08T12:00:00Z"));
        }
    }

    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired MaintenanceJobs maintenance;
    @Autowired VerificationService verification;
    @Autowired Clock clock;

    @Test
    @DisplayName("an uncaptured authorisation is released when its window closes")
    void expiredHoldsAreReleased() {
        accounts.create("exp:cash", "cash", "USD", AccountType.ASSET, true);
        accounts.create("exp:alice", "alice", "USD", AccountType.LIABILITY, false);
        fund("exp:alice", 10_000);

        Transfer hold = transfers.create(new TransferCommand(
                "auth-1", "USD", "authorisation",
                List.of(new LegCommand("exp:alice", Direction.DEBIT, 4_000),
                        new LegCommand("exp:cash", Direction.CREDIT, 4_000)),
                true, Duration.ofMinutes(30)));

        assertThat(accounts.balanceFromDatabase("exp:alice").availableMinor()).isEqualTo(6_000);

        // Not yet due: the sweeper must leave it alone.
        ((MutableClock) clock).advance(Duration.ofMinutes(29));
        maintenance.expirePendingTransfers();
        assertThat(transfers.findById(hold.id()).state()).isEqualTo(TransferState.PENDING);
        assertThat(accounts.balanceFromDatabase("exp:alice").availableMinor()).isEqualTo(6_000);

        // Past the window: released.
        ((MutableClock) clock).advance(Duration.ofMinutes(2));
        maintenance.expirePendingTransfers();

        assertThat(transfers.findById(hold.id()).state()).isEqualTo(TransferState.EXPIRED);
        assertThat(accounts.balanceFromDatabase("exp:alice").availableMinor())
                .describedAs("the hold was released but the funds were not returned")
                .isEqualTo(10_000);

        // Settled balance untouched throughout: an expiry moves no money, it
        // only stops earmarking it.
        assertThat(accounts.balanceFromDatabase("exp:alice").settledMinor()).isEqualTo(10_000);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM posting WHERE transfer_id = ?::uuid", Long.class, hold.id().toString()))
                .describedAs("an expired transfer wrote postings")
                .isEqualTo(0L);

        assertThat(verification.verify().healthy()).isTrue();
    }

    @Test
    @DisplayName("an expired authorisation can no longer be captured")
    void expiredHoldsCannotBeCaptured() {
        accounts.create("exp2:cash", "cash", "USD", AccountType.ASSET, true);
        accounts.create("exp2:alice", "alice", "USD", AccountType.LIABILITY, false);
        fund("exp2:alice", 10_000);

        Transfer hold = transfers.create(new TransferCommand(
                "auth-2", "USD", "authorisation",
                List.of(new LegCommand("exp2:alice", Direction.DEBIT, 1_000),
                        new LegCommand("exp2:cash", Direction.CREDIT, 1_000)),
                true, Duration.ofMinutes(5)));

        ((MutableClock) clock).advance(Duration.ofMinutes(6));
        maintenance.expirePendingTransfers();

        // A late capture must fail rather than resurrect the hold: the payer's
        // funds were given back and may already have been spent elsewhere.
        assertThatThrownBy(() -> transfers.capture(hold.id()))
                .isInstanceOf(LedgerException.InvalidTransferState.class)
                .hasMessageContaining("EXPIRED");
    }

    private void fund(String wallet, long amountMinor) {
        String cash = wallet.substring(0, wallet.indexOf(':')) + ":cash";
        transfers.create(new TransferCommand(
                "fund-" + wallet, "USD", "opening balance",
                List.of(new LegCommand(cash, Direction.DEBIT, amountMinor),
                        new LegCommand(wallet, Direction.CREDIT, amountMinor)),
                false, Duration.ZERO));
    }
}
