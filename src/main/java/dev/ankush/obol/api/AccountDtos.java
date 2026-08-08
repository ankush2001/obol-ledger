package dev.ankush.obol.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ankush.obol.domain.BalanceView;
import dev.ankush.obol.domain.Enums.AccountType;
import dev.ankush.obol.domain.LedgerAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class AccountDtos {

    private AccountDtos() {
    }

    @Schema(description = "Opens an account in the chart of accounts.")
    public record CreateAccountRequest(

            @Schema(example = "wallet:alice", description = """
                    Stable human-readable handle. Transfers name accounts by code rather \
                    than id so a caller never has to store a UUID to move money.""")
            @NotBlank @Size(max = 128)
            @Pattern(regexp = "^[a-z0-9][a-z0-9:_.-]*$",
                    message = "must be lowercase, starting alphanumeric, and may contain : _ . -")
            String code,

            @NotBlank @Size(max = 256) String name,

            @Schema(example = "USD")
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter ISO 4217 code")
            String currency,

            @NotNull AccountType type,

            @Schema(description = """
                    Whether this account may go below zero. True for the accounts that face \
                    the outside world -- cash-in, revenue, fees -- where money genuinely \
                    arrives from beyond the ledger. Leave false for anything holding \
                    somebody's money.""")
            boolean allowNegative
    ) {
    }

    public record AccountResponse(
            UUID id,
            String code,
            String name,
            String currency,
            AccountType type,
            String normalSide,
            boolean allowNegative,
            Instant createdAt
    ) {
        public static AccountResponse from(LedgerAccount a) {
            return new AccountResponse(a.id(), a.code(), a.name(), a.currency(), a.type(),
                    a.normalSide().name(), a.allowNegative(), a.createdAt());
        }
    }

    /**
     * A balance, reported three ways because the three genuinely differ and
     * conflating them is how a system lets someone spend money twice.
     *
     * @param settledMinor   what has actually posted
     * @param reservedMinor  held by pending authorisations
     * @param availableMinor settled minus reserved: what may be spent now
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BalanceResponse(
            UUID accountId,
            String accountCode,
            String currency,
            long settledMinor,
            long reservedMinor,
            long availableMinor,
            String settled,
            String reserved,
            String available
    ) {
        public static BalanceResponse from(BalanceView b) {
            return new BalanceResponse(
                    b.accountId(), b.accountCode(), b.currency(),
                    b.settledMinor(), b.reservedOutflowMinor(), b.availableMinor(),
                    Money.format(b.settledMinor(), b.currency()),
                    Money.format(b.reservedOutflowMinor(), b.currency()),
                    Money.format(b.availableMinor(), b.currency()));
        }
    }
}
