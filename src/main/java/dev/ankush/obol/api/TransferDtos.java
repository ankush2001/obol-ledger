package dev.ankush.obol.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ankush.obol.domain.Enums.Direction;
import dev.ankush.obol.domain.Enums.TransferState;
import dev.ankush.obol.domain.Posting;
import dev.ankush.obol.domain.Transfer;
import dev.ankush.obol.service.TransferCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransferDtos {

    private TransferDtos() {
    }

    @Schema(description = """
            Moves money. The legs must sum to zero: every unit debited somewhere is \
            credited somewhere else. Two legs is the ordinary case, but a payment that \
            splits out a fee is three, and doing it as one transfer is what makes the \
            fee and the payment impossible to half-apply.""")
    public record CreateTransferRequest(

            @Schema(example = "psp_ch_9f2a41", description =
                    "Your own reference. Reconciliation matches on it.")
            @Size(max = 256) String externalId,

            @Schema(example = "USD")
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter ISO 4217 code")
            String currency,

            @Size(max = 512) String description,

            @Schema(description = "At least two legs, summing to zero.")
            @NotNull @Size(min = 2, message = "a transfer needs at least two legs")
            @Valid List<LegRequest> legs,

            @Schema(description = """
                    When true the transfer is authorised but not settled: the funds are \
                    reserved and no postings are written until it is captured. This is the \
                    authorisation half of an authorise-then-capture flow.""")
            boolean pending,

            @Schema(example = "PT30M", description =
                    "How long the authorisation holds before it is released. Pending transfers only.")
            Duration pendingTtl
    ) {

        private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

        public TransferCommand toCommand() {
            return new TransferCommand(
                    externalId,
                    currency.toUpperCase(),
                    description,
                    legs.stream()
                            .map(l -> new TransferCommand.LegCommand(
                                    l.accountCode(), l.direction(), l.amountMinor()))
                            .toList(),
                    pending,
                    pendingTtl != null ? pendingTtl : DEFAULT_TTL);
        }
    }

    public record LegRequest(
            @Schema(example = "wallet:alice")
            @NotBlank String accountCode,

            @NotNull Direction direction,

            @Schema(example = "1000", description =
                    "Always in minor units -- cents, not dollars. Never a decimal.")
            @Positive(message = "must be greater than zero") long amountMinor
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransferResponse(
            UUID id,
            String externalId,
            TransferState state,
            String currency,
            long amountMinor,
            String amount,
            String description,
            List<LegResponse> legs,
            Instant pendingExpiresAt,
            Instant createdAt,
            Instant postedAt,
            Instant voidedAt
    ) {
        public static TransferResponse from(Transfer t) {
            return new TransferResponse(
                    t.id(), t.externalId(), t.state(), t.currency(), t.amountMinor(),
                    Money.format(t.amountMinor(), t.currency()),
                    t.description(),
                    t.legs().stream().map(LegResponse::from).toList(),
                    t.pendingExpiresAt(), t.createdAt(), t.postedAt(), t.voidedAt());
        }
    }

    public record LegResponse(String accountCode, Direction direction, long amountMinor, String amount) {
        public static LegResponse from(Transfer.Leg leg) {
            return new LegResponse(leg.accountCode(), leg.direction(), leg.amountMinor(),
                    Money.format(leg.amountMinor(), leg.currency()));
        }
    }

    /**
     * A journal entry as it appears on a statement.
     *
     * @param balanceAfterMinor the account's signed balance immediately after
     *                          this entry, recorded when it was written
     */
    public record PostingResponse(
            long id,
            UUID transferId,
            Direction direction,
            long amountMinor,
            String amount,
            long balanceAfterMinor,
            Instant createdAt
    ) {
        public static PostingResponse from(Posting p) {
            return new PostingResponse(
                    p.id(), p.transferId(),
                    p.isDebit() ? Direction.DEBIT : Direction.CREDIT,
                    Math.abs(p.amountMinor()),
                    Money.format(Math.abs(p.amountMinor()), p.currency()),
                    p.balanceAfterMinor(),
                    p.createdAt());
        }
    }
}
