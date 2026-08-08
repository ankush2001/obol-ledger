package dev.ankush.obol.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IdempotencyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Record> MAPPER = (rs, n) -> new Record(
            rs.getString("key"),
            rs.getString("request_hash"),
            rs.getString("state"),
            (Integer) rs.getObject("response_status"),
            rs.getString("response_body"),
            rs.getObject("transfer_id", UUID.class),
            rs.getTimestamp("expires_at").toInstant());

    /**
     * Attempts to claim the key, returning false if someone else already has it.
     *
     * <p>{@code ON CONFLICT DO NOTHING} against the primary key is what makes
     * this safe: the winner is decided by the database's unique index, not by
     * a read-then-write in application code that two concurrent retries would
     * both pass. Exactly one caller sees true, however many arrive at once.
     *
     * <p>Must be committed before the real work begins, or a concurrent retry
     * cannot see the claim -- see {@code IdempotencyService}.
     */
    public boolean tryClaim(String key, String requestHash, Instant expiresAt) {
        int inserted = jdbc.update("""
                INSERT INTO idempotency_key (key, request_hash, state, expires_at)
                VALUES (:key, :hash, 'IN_FLIGHT', :expiresAt)
                ON CONFLICT (key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("hash", requestHash)
                .addValue("expiresAt", Timestamp.from(expiresAt)));
        return inserted == 1;
    }

    public Optional<Record> find(String key) {
        return jdbc.query("""
                SELECT key, request_hash, state, response_status, response_body, transfer_id, expires_at
                  FROM idempotency_key WHERE key = :key
                """, Map.of("key", key), MAPPER).stream().findFirst();
    }

    /**
     * Records the outcome. Called inside the same transaction as the transfer
     * it describes, so the stored response and the ledger state it reports can
     * never disagree -- either both are committed or neither is.
     */
    public void complete(String key, int status, String body, UUID transferId) {
        jdbc.update("""
                UPDATE idempotency_key
                   SET state = 'COMPLETED', response_status = :status,
                       response_body = :body, transfer_id = :transferId
                 WHERE key = :key
                """, new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("status", status)
                .addValue("body", body)
                .addValue("transferId", transferId));
    }

    /**
     * Drops a claim whose work failed, so an honest retry is not locked out by
     * a request that never produced anything.
     */
    public void release(String key) {
        jdbc.update("DELETE FROM idempotency_key WHERE key = :key AND state = 'IN_FLIGHT'",
                Map.of("key", key));
    }

    /** Housekeeping: keys outlive their usefulness after the retry window. */
    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM idempotency_key WHERE expires_at < :now",
                Map.of("now", Timestamp.from(now)));
    }

    public record Record(
            String key,
            String requestHash,
            String state,
            Integer responseStatus,
            String responseBody,
            UUID transferId,
            Instant expiresAt
    ) {
        public boolean isCompleted() {
            return "COMPLETED".equals(state);
        }
    }
}
