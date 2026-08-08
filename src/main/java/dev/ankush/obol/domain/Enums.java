package dev.ankush.obol.domain;

/**
 * The vocabulary of the ledger. Kept in one file because these five enums are
 * meaningless apart from each other -- {@link Direction} and
 * {@link NormalSide} in particular only make sense as a pair.
 */
public final class Enums {

    private Enums() {
    }

    /** Standard accounting classification. */
    public enum AccountType {
        ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE
    }

    /**
     * Which direction increases an account in its own terms.
     *
     * <p>Assets and expenses are debit-normal: a debit makes them larger.
     * Liabilities, equity and revenue are credit-normal. This is why a
     * customer wallet -- money the business owes the customer, so a liability
     * -- holds a negative signed balance internally while showing the
     * customer a positive one.
     */
    public enum NormalSide {
        DEBIT('D'), CREDIT('C');

        private final char code;

        NormalSide(char code) {
            this.code = code;
        }

        public char code() {
            return code;
        }

        public static NormalSide fromCode(String code) {
            return switch (code.trim()) {
                case "D" -> DEBIT;
                case "C" -> CREDIT;
                default -> throw new IllegalArgumentException("unknown normal side: " + code);
            };
        }
    }

    /**
     * The direction of a single leg.
     *
     * <p>{@link #signum()} is the bridge between the API's human vocabulary
     * and the signed integers actually stored: a debit is positive, a credit
     * is negative, and a balanced transfer therefore sums to zero.
     */
    public enum Direction {
        DEBIT(1), CREDIT(-1);

        private final int signum;

        Direction(int signum) {
            this.signum = signum;
        }

        public int signum() {
            return signum;
        }
    }

    /**
     * Transfer lifecycle.
     *
     * <p>A one-phase transfer goes straight to {@link #POSTED}. A two-phase
     * transfer is created {@link #PENDING} -- funds reserved but no postings
     * written -- and later captured to {@link #POSTED} or released to
     * {@link #VOIDED}. {@link #EXPIRED} is a void the sweeper performed
     * because nobody captured it in time.
     */
    public enum TransferState {
        PENDING, POSTED, VOIDED, EXPIRED;

        public boolean isTerminal() {
            return this != PENDING;
        }
    }
}
