package dev.ankush.obol.domain;

import dev.ankush.obol.domain.Enums.NormalSide;

import java.util.UUID;

/**
 * An account's balance, joined to the account so the signed storage figures
 * can be turned back into numbers a human would recognise.
 *
 * <p>Everything below hangs off one idea: the database stores a single signed
 * {@code postedMinor} where debits are positive, and each account decides
 * which sign means "more" via its {@link NormalSide}.
 *
 * @param postedMinor          signed sum of settled postings
 * @param pendingDebitsMinor   reserved by pending transfers, always >= 0
 * @param pendingCreditsMinor  reserved by pending transfers, always >= 0
 */
public record BalanceView(
        UUID accountId,
        String accountCode,
        String currency,
        NormalSide normalSide,
        boolean allowNegative,
        long postedMinor,
        long pendingDebitsMinor,
        long pendingCreditsMinor,
        long version
) {

    /**
     * The settled balance in the account's own terms: positive means the
     * account holds what it is supposed to hold. A customer wallet with
     * {@code postedMinor == -5000} reports 5000, because a liability grows
     * with credits.
     */
    public long settledMinor() {
        return normalSide == NormalSide.DEBIT ? postedMinor : -postedMinor;
    }

    /**
     * The amount pending transfers could still take away. Only outflows count:
     * a pending credit that would pay money *into* this account is not spendable
     * until it settles, so it is deliberately ignored here. Reserving
     * pessimistically is the whole point of a two-phase transfer.
     */
    public long reservedOutflowMinor() {
        return normalSide == NormalSide.DEBIT ? pendingCreditsMinor : pendingDebitsMinor;
    }

    /** What may actually be spent right now. */
    public long availableMinor() {
        return settledMinor() - reservedOutflowMinor();
    }

    /**
     * Whether taking {@code outflowMinor} out of this account is permitted.
     * Accounts flagged {@code allowNegative} represent the boundary with the
     * outside world and are never constrained.
     */
    public boolean canWithdraw(long outflowMinor) {
        return allowNegative || availableMinor() >= outflowMinor;
    }
}
