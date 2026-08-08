package dev.ankush.obol.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for the failures a caller can actually do something about.
 *
 * <p>Each subclass fixes its own status and a stable machine-readable
 * {@code code}. Clients of a payments API retry on some failures and must
 * never retry on others, so the distinction cannot be left to prose in a
 * message string.
 */
public abstract class LedgerException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected LedgerException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    // ------------------------------------------------------------------

    public static class AccountNotFound extends LedgerException {
        public AccountNotFound(String ref) {
            super(HttpStatus.NOT_FOUND, "account_not_found", "no such account: " + ref);
        }
    }

    public static class TransferNotFound extends LedgerException {
        public TransferNotFound(String ref) {
            super(HttpStatus.NOT_FOUND, "transfer_not_found", "no such transfer: " + ref);
        }
    }

    public static class DuplicateAccountCode extends LedgerException {
        public DuplicateAccountCode(String code) {
            super(HttpStatus.CONFLICT, "account_code_taken", "account code already in use: " + code);
        }
    }

    /** The legs do not sum to zero, so the request was never a transfer. */
    public static class UnbalancedTransfer extends LedgerException {
        public UnbalancedTransfer(long imbalanceMinor) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "unbalanced_transfer",
                    "debits minus credits must be zero, got " + imbalanceMinor);
        }
    }

    public static class CurrencyMismatch extends LedgerException {
        public CurrencyMismatch(String accountCode, String accountCurrency, String transferCurrency) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "currency_mismatch",
                    "account %s holds %s but the transfer is in %s"
                            .formatted(accountCode, accountCurrency, transferCurrency));
        }
    }

    /**
     * Not an error condition in the system -- the ledger worked exactly as
     * intended and refused to let an account go overdrawn.
     */
    public static class InsufficientFunds extends LedgerException {
        public InsufficientFunds(String accountCode, long availableMinor, long requestedMinor) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient_funds",
                    "account %s has %d available but %d was requested"
                            .formatted(accountCode, availableMinor, requestedMinor));
        }
    }

    /** Capture or void arrived after the transfer had already settled. */
    public static class InvalidTransferState extends LedgerException {
        public InvalidTransferState(String action, String actualState) {
            super(HttpStatus.CONFLICT, "invalid_transfer_state",
                    "cannot %s a transfer in state %s".formatted(action, actualState));
        }
    }

    /**
     * The same idempotency key arrived with a different request body. Almost
     * always a client generating one key for two logically different
     * payments -- replaying either stored answer would be a lie, so neither
     * is returned.
     */
    public static class IdempotencyKeyReused extends LedgerException {
        public IdempotencyKeyReused(String key) {
            super(HttpStatus.CONFLICT, "idempotency_key_reused",
                    "idempotency key %s was already used with a different request body".formatted(key));
        }
    }

    /**
     * A retry landed while the original request was still running. The right
     * answer is 409 plus a Retry-After, not a second execution.
     */
    public static class RequestInFlight extends LedgerException {
        public RequestInFlight(String key) {
            super(HttpStatus.CONFLICT, "request_in_flight",
                    "a request with idempotency key %s is still in progress".formatted(key));
        }
    }
}
