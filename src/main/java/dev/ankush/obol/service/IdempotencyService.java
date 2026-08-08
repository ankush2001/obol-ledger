package dev.ankush.obol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ankush.obol.error.LedgerException;
import dev.ankush.obol.repo.IdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Makes a write endpoint safe to retry.
 *
 * <p>A payments client that times out cannot tell a lost request from a lost
 * response, so it will retry, and the ledger must make the second attempt a
 * no-op that returns the first attempt's answer. The three cases:
 *
 * <ul>
 *   <li><b>New key</b> -- run the work, store the response.</li>
 *   <li><b>Same key, same body</b> -- a genuine retry; replay the stored
 *       response without touching a balance.</li>
 *   <li><b>Same key, different body</b> -- the client reused a key for a
 *       different payment. Refuse both, loudly: executing it would be a
 *       duplicate payment, and replaying the old response would be a lie about
 *       one that never happened.</li>
 * </ul>
 *
 * <p>The transaction structure is what makes this hold under concurrency
 * rather than only in sequence. The claim commits on its own, immediately, so
 * a racing retry can see it; the work and the recorded response share one
 * transaction, so a stored response always describes a committed transfer.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRepository keys;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration retention;
    private final TransactionTemplate transaction;

    public IdempotencyService(IdempotencyRepository keys,
                              ObjectMapper json,
                              Clock clock,
                              PlatformTransactionManager txManager,
                              @Value("${obol.idempotency.retention:PT24H}") Duration retention) {
        this.keys = keys;
        this.json = json;
        this.clock = clock;
        this.retention = retention;
        this.transaction = new TransactionTemplate(txManager);
    }

    /**
     * Runs {@code work} at most once per key.
     *
     * <p>Note what this method is <em>not</em>: annotated {@code @Transactional}.
     * The three steps below run as separate, sequential transactions, and that
     * is deliberate. The obvious design -- one outer transaction with the claim
     * nested inside it as {@code REQUIRES_NEW} -- needs two connections per
     * caller at the same instant, because the outer transaction holds its
     * connection while the inner one asks for another. Twenty concurrent
     * retries against a ten-connection pool then deadlock on the pool itself:
     * every thread holds one connection and waits forever for a second that
     * only another thread can release. Running the steps in sequence means one
     * connection per caller, and the pool merely queues.
     *
     * @param type the response type, needed to rehydrate a replayed body
     */
    public <T> Outcome<T> execute(String key, Object request, Class<T> type, Supplier<T> work) {
        String hash = hash(request);

        // Step 1, committed alone so a concurrent retry can observe it. Held
        // open inside a larger transaction this row would stay invisible until
        // commit, and both attempts would go on to move money.
        boolean claimed = Boolean.TRUE.equals(transaction.execute(
                status -> keys.tryClaim(key, hash, clock.instant().plus(retention))));

        if (!claimed) {
            return replay(key, hash, type);
        }

        try {
            // Step 2: the work and the recorded response share one transaction,
            // so a stored response always describes a committed transfer.
            T result = transaction.execute(status -> {
                T value = work.get();
                keys.complete(key, 200, serialise(value), transferIdOf(value));
                return value;
            });
            return new Outcome<>(result, false);
        } catch (RuntimeException e) {
            // Step 3, only on failure. Step 2 rolled back, but the claim from
            // step 1 is already committed and would otherwise sit IN_FLIGHT
            // forever, meeting every honest retry with a 409.
            transaction.executeWithoutResult(status -> keys.release(key));
            throw e;
        }
    }

    private <T> Outcome<T> replay(String key, String expectedHash, Class<T> type) {
        IdempotencyRepository.Record existing = keys.find(key)
                // Vanishingly rare: the claim was deleted between our failed
                // insert and this read. Treating it as in-flight asks the
                // client to retry, which is the safe direction.
                .orElseThrow(() -> new LedgerException.RequestInFlight(key));

        if (!existing.requestHash().equals(expectedHash)) {
            throw new LedgerException.IdempotencyKeyReused(key);
        }
        if (!existing.isCompleted()) {
            throw new LedgerException.RequestInFlight(key);
        }

        log.info("replaying stored response for idempotency key {}", key);
        return new Outcome<>(deserialise(existing.responseBody(), type), true);
    }

    /**
     * Fingerprints the request body.
     *
     * <p>Serialised through Jackson rather than hashing the raw bytes, so that
     * two requests differing only in whitespace or field order are recognised
     * as the same retry -- which is what a client using a different HTTP
     * library on the second attempt will actually send.
     */
    private String hash(Object request) {
        try {
            byte[] canonical = json.writeValueAsBytes(request);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("request body could not be fingerprinted", e);
        }
    }

    /** Links the key to the transfer it produced, for support and audit. */
    private UUID transferIdOf(Object result) {
        try {
            var node = json.valueToTree(result).get("id");
            return node == null || node.isNull() ? null : UUID.fromString(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private String serialise(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not store idempotent response", e);
        }
    }

    private <T> T deserialise(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("stored idempotent response is unreadable", e);
        }
    }

    /** @param replayed true when this answer came from the store, not from work */
    public record Outcome<T>(T value, boolean replayed) {
    }

    /** Housekeeping, driven by {@code MaintenanceJobs}. */
    @Transactional
    public int purgeExpired() {
        return keys.deleteExpired(clock.instant());
    }
}
