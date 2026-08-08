package dev.ankush.obol.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class OutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<OutboxRow> MAPPER = (rs, n) -> new OutboxRow(
            rs.getLong("id"),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getInt("attempts"));

    /**
     * Appends an event. Always called inside the transaction that made the
     * change being announced -- that is the whole trick. There is no window in
     * which the ledger has moved but the event is lost, and none in which an
     * event describes a transfer that rolled back.
     */
    public void append(String aggregateType, String aggregateId, String eventType, String payload) {
        jdbc.update("""
                INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
                VALUES (:aggregateType, :aggregateId, :eventType, :payload)
                """, new MapSqlParameterSource()
                .addValue("aggregateType", aggregateType)
                .addValue("aggregateId", aggregateId)
                .addValue("eventType", eventType)
                .addValue("payload", payload));
    }

    /**
     * Claims a batch of unpublished events for this relay.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets several relay instances drain the
     * same table at once: each takes rows nobody else holds instead of queueing
     * behind them. Ordered by id so events for a given account are published in
     * the order they happened.
     */
    public List<OutboxRow> claimUnpublished(int limit) {
        return jdbc.query("""
                SELECT id, aggregate_type, aggregate_id, event_type, payload, created_at, attempts
                  FROM outbox_event
                 WHERE published_at IS NULL
                 ORDER BY id
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED
                """, Map.of("limit", limit), MAPPER);
    }

    public void markPublished(List<Long> ids, Instant at) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE outbox_event SET published_at = :at WHERE id IN (:ids)",
                Map.of("at", Timestamp.from(at), "ids", ids));
    }

    /**
     * Records a failed delivery without consuming the event. Delivery is
     * at-least-once by design, so consumers must be idempotent; losing an event
     * to a transient HTTP error would be far worse than delivering it twice.
     */
    public void recordFailure(List<Long> ids, String error) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE outbox_event
                   SET attempts = attempts + 1, last_error = :error
                 WHERE id IN (:ids)
                """, Map.of("error", truncate(error), "ids", ids));
    }

    public long unpublishedCount() {
        Long c = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
        return c == null ? 0 : c;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    public record OutboxRow(
            long id,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            Instant createdAt,
            int attempts
    ) {
    }
}
