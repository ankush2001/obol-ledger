package dev.ankush.obol.api;

import dev.ankush.obol.api.TransferDtos.CreateTransferRequest;
import dev.ankush.obol.api.TransferDtos.TransferResponse;
import dev.ankush.obol.service.IdempotencyService;
import dev.ankush.obol.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/transfers")
@Validated
@Tag(name = "Transfers", description = "Moving money, in one phase or two")
public class TransferController {

    private final TransferService transfers;
    private final IdempotencyService idempotency;

    public TransferController(TransferService transfers, IdempotencyService idempotency) {
        this.transfers = transfers;
        this.idempotency = idempotency;
    }

    /**
     * The Idempotency-Key header is required, not optional.
     *
     * <p>Stripe and most public APIs make it optional for the sake of casual
     * callers. This one does not, because the caller who omits it is exactly
     * the caller who has not thought about what their client does on a
     * timeout -- and the cost of that omission is a duplicate payment. Being
     * turned away with a 400 at integration time is a far better outcome.
     */
    @PostMapping
    @Operation(summary = "Create a transfer", description = """
            Legs must sum to zero. Set `pending` to authorise without settling.

            Requires an `Idempotency-Key` header. Retrying with the same key returns the \
            original response without moving money again; reusing a key with a different \
            body is a 409.""")
    public ResponseEntity<TransferResponse> create(
            @Parameter(description = "A unique key per logical payment, e.g. a UUID.", required = true)
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {

        var outcome = idempotency.execute(idempotencyKey, request, TransferResponse.class,
                () -> TransferResponse.from(transfers.create(request.toCommand())));

        TransferResponse body = outcome.value();
        return ResponseEntity
                .created(URI.create("/v1/transfers/" + body.id()))
                // Lets a client tell "I created this" from "I asked twice",
                // which matters when reconciling its own logs against ours.
                .header("Idempotent-Replay", String.valueOf(outcome.replayed()))
                .body(body);
    }

    /**
     * No idempotency key here, and none needed: capture is a compare-and-set on
     * the transfer's state, so the second call finds it already POSTED and
     * gets a 409 rather than posting again.
     */
    @PostMapping("/{id}/capture")
    @Operation(summary = "Capture a pending transfer",
            description = "Settles a reservation. Already-settled transfers give 409.")
    public TransferResponse capture(@PathVariable UUID id) {
        return TransferResponse.from(transfers.capture(id));
    }

    @PostMapping("/{id}/void")
    @Operation(summary = "Void a pending transfer",
            description = "Releases the reservation. No postings are written or reversed.")
    public TransferResponse voidTransfer(@PathVariable UUID id) {
        return TransferResponse.from(transfers.voidTransfer(id));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a transfer with its legs")
    public TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.from(transfers.findById(id));
    }

    @GetMapping
    @Operation(summary = "List recent transfers")
    public List<TransferResponse> list(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return transfers.findRecent(limit, offset).stream().map(TransferResponse::from).toList();
    }
}
