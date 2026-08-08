package dev.ankush.obol.unit;

import dev.ankush.obol.api.Money;
import dev.ankush.obol.domain.BalanceView;
import dev.ankush.obol.domain.Enums.NormalSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The arithmetic, tested without a database.
 *
 * <p>Sign conventions and currency scales are where a ledger acquires bugs
 * that no integration test notices, because every number still balances --
 * just against the wrong account or with the decimal point moved.
 */
class MoneyAndBalanceTest {

    @Nested
    @DisplayName("minor-unit formatting")
    class MoneyFormatting {

        @ParameterizedTest(name = "{0} {1} formats as {2}")
        @CsvSource({
                "1234,  USD, 12.34",
                "0,     USD, 0.00",
                "-500,  USD, -5.00",
                "5,     USD, 0.05",
                "1234,  JPY, 1234",     // yen has no minor unit at all
                "1234,  CLP, 1234",     // nor does the Chilean peso
                "100000000000, USD, 1000000000.00"
        })
        void formatsAccordingToTheCurrencysScale(long minor, String currency, String expected) {
            assertThat(Money.format(minor, currency)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} {1} parses to {2}")
        @CsvSource({
                "12.34, USD, 1234",
                "0.05,  USD, 5",
                "1234,  JPY, 1234"
        })
        void parsesBackToMinorUnits(String amount, String currency, long expected) {
            assertThat(Money.parse(amount, currency)).isEqualTo(expected);
        }

        @Test
        @DisplayName("refuses precision the currency does not have, rather than rounding it away")
        void rejectsImpossiblePrecision() {
            // Silently rounding 1.005 to 1.00 or 1.01 is how half a cent goes
            // missing per transaction, which is exactly the class of bug a
            // ledger exists to make impossible.
            assertThatThrownBy(() -> Money.parse("1.005", "USD"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2 decimal places");
        }
    }

    @Nested
    @DisplayName("balances by normal side")
    class Balances {

        @Test
        @DisplayName("a debit-normal account reads its signed balance directly")
        void debitNormal() {
            BalanceView cash = balance(NormalSide.DEBIT, 10_000, 0, 2_500, false);

            assertThat(cash.settledMinor()).isEqualTo(10_000);
            // Money leaves a cash account by being credited.
            assertThat(cash.reservedOutflowMinor()).isEqualTo(2_500);
            assertThat(cash.availableMinor()).isEqualTo(7_500);
        }

        @Test
        @DisplayName("a credit-normal account reads it inverted")
        void creditNormal() {
            // A customer wallet holding 100.00 stores -10000: the business owes
            // them, and a liability grows with credits.
            BalanceView wallet = balance(NormalSide.CREDIT, -10_000, 2_500, 0, false);

            assertThat(wallet.settledMinor()).isEqualTo(10_000);
            // Money leaves a wallet by being debited.
            assertThat(wallet.reservedOutflowMinor()).isEqualTo(2_500);
            assertThat(wallet.availableMinor()).isEqualTo(7_500);
        }

        @Test
        @DisplayName("an inbound pending credit is not spendable yet")
        void incomingPendingIsNotAvailable() {
            // 50.00 settled, with 30.00 pending *in*. Available stays 50.00:
            // treating unsettled money as spendable is how a ledger lets
            // someone spend a payment that later fails.
            BalanceView wallet = balance(NormalSide.CREDIT, -5_000, 0, 3_000, false);

            assertThat(wallet.settledMinor()).isEqualTo(5_000);
            assertThat(wallet.reservedOutflowMinor()).isZero();
            assertThat(wallet.availableMinor()).isEqualTo(5_000);
        }

        @Test
        @DisplayName("the overdraft rule is enforced unless the account opts out")
        void withdrawalLimits() {
            BalanceView wallet = balance(NormalSide.CREDIT, -5_000, 0, 0, false);
            assertThat(wallet.canWithdraw(5_000)).isTrue();
            assertThat(wallet.canWithdraw(5_001)).isFalse();

            // Cash-in and revenue accounts face the outside world; money
            // genuinely arrives from beyond the ledger's knowledge.
            BalanceView house = balance(NormalSide.DEBIT, 0, 0, 0, true);
            assertThat(house.canWithdraw(Long.MAX_VALUE)).isTrue();
        }

        private BalanceView balance(NormalSide side, long posted, long pendingDebits,
                                    long pendingCredits, boolean allowNegative) {
            return new BalanceView(UUID.randomUUID(), "test:account", "USD", side,
                    allowNegative, posted, pendingDebits, pendingCredits, 0);
        }
    }
}
