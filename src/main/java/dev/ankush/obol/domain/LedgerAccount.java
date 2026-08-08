package dev.ankush.obol.domain;

import dev.ankush.obol.domain.Enums.AccountType;
import dev.ankush.obol.domain.Enums.NormalSide;

import java.time.Instant;
import java.util.UUID;

/**
 * An account in the chart of accounts.
 *
 * @param allowNegative whether the ledger will let this account's available
 *                      balance go below zero. True for the accounts that
 *                      represent the outside world -- cash-in, revenue, fees
 *                      -- because money genuinely arrives from nowhere as far
 *                      as this ledger can see. False for customer wallets,
 *                      where a negative balance means we have let someone
 *                      spend money they do not have.
 */
public record LedgerAccount(
        UUID id,
        String code,
        String name,
        String currency,
        AccountType type,
        NormalSide normalSide,
        boolean allowNegative,
        Instant createdAt
) {
}
