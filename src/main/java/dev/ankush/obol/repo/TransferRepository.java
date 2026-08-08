package dev.ankush.obol.repo;

import dev.ankush.obol.domain.Enums.Direction;
import dev.ankush.obol.domain.Enums.TransferState;
import dev.ankush.obol.domain.Transfer;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TransferRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Transfer> MAPPER = (rs, n) -> new Transfer(
            rs.getObject("id", UUID.class),
            rs.getString("external_id"),
            TransferState.valueOf(rs.getString("state")),
            rs.getString("currency").trim(),
            rs.getLong("amount_minor"),
            rs.getString("description"),
            instant(rs.getTimestamp("pending_expires_at")),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("posted_at")),
            instant(rs.getTimestamp("voided_at")),
            List.of());

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static final String COLUMNS = """
            id, external_id, state, currency, amount_minor, description,
            pending_expires_at, created_at, posted_at, voided_at
            """;

    public void insert(Transfer t) {
        jdbc.update("""
                INSERT INTO transfer (id, external_id, state, currency, amount_minor, description,
                                      pending_expires_at, posted_at, voided_at)
                VALUES (:id, :externalId, :state, :currency, :amount, :description,
                        :pendingExpiresAt, :postedAt, :voidedAt)
                """, new MapSqlParameterSource()
                .addValue("id", t.id())
                .addValue("externalId", t.externalId())
                .addValue("state", t.state().name())
                .addValue("currency", t.currency())
                .addValue("amount", t.amountMinor())
                .addValue("description", t.description())
                .addValue("pendingExpiresAt", timestamp(t.pendingExpiresAt()))
                .addValue("postedAt", timestamp(t.postedAt()))
                .addValue("voidedAt", timestamp(t.voidedAt())));
    }

    public Optional<Transfer> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transfer WHERE id = :id",
                Map.of("id", id), MAPPER).stream().findFirst();
    }

    /**
     * Reads the transfer and takes a row lock on it, so that two concurrent
     * captures of the same pending transfer cannot both observe PENDING and
     * both proceed. The state check that follows is only meaningful while this
     * lock is held.
     */
    public Optional<Transfer> findByIdForUpdate(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transfer WHERE id = :id FOR UPDATE",
                Map.of("id", id), MAPPER).stream().findFirst();
    }

    public List<Transfer> findRecent(int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transfer ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
                Map.of("limit", limit, "offset", offset), MAPPER);
    }

    /**
     * Moves a PENDING transfer to POSTED.
     *
     * <p>The {@code state = 'PENDING'} predicate in the WHERE clause is a
     * compare-and-set, not decoration: if anything settled this transfer
     * between the read and this write, zero rows update and the caller finds
     * out instead of double-posting.
     */
    public int markPosted(UUID id, Instant at) {
        return jdbc.update("""
                UPDATE transfer
                   SET state = 'POSTED', posted_at = :at, pending_expires_at = NULL
                 WHERE id = :id AND state = 'PENDING'
                """, Map.of("id", id, "at", Timestamp.from(at)));
    }

    public int markVoided(UUID id, Instant at, TransferState terminalState) {
        return jdbc.update("""
                UPDATE transfer
                   SET state = :state, voided_at = :at, pending_expires_at = NULL
                 WHERE id = :id AND state = 'PENDING'
                """, Map.of("id", id, "at", Timestamp.from(at), "state", terminalState.name()));
    }

    /**
     * Pending transfers whose capture window has closed. Ordered oldest-first
     * and limited, so a long outage does not turn the first sweep back into an
     * unbounded transaction.
     */
    public List<Transfer> findExpired(Instant now, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM transfer
                 WHERE state = 'PENDING' AND pending_expires_at <= :now
                 ORDER BY pending_expires_at
                 LIMIT :limit
                """, Map.of("now", Timestamp.from(now), "limit", limit), MAPPER);
    }

    // ------------------------------------------------------------ legs

    /**
     * Writes the transfer's legs in one batch. The deferred balance trigger
     * fires at COMMIT, so a half-inserted set is fine right up until the
     * transaction tries to finish -- at which point an unbalanced instruction
     * is rejected by the database.
     */
    public void insertLegs(UUID transferId, List<Transfer.Leg> legs) {
        MapSqlParameterSource[] batch = new MapSqlParameterSource[legs.size()];
        for (int i = 0; i < legs.size(); i++) {
            Transfer.Leg leg = legs.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("transferId", transferId)
                    .addValue("seq", i)
                    .addValue("accountId", leg.accountId())
                    .addValue("currency", leg.currency())
                    .addValue("amount", leg.signedMinor());
        }
        jdbc.batchUpdate("""
                INSERT INTO transfer_leg (transfer_id, seq, account_id, currency, amount_minor)
                VALUES (:transferId, :seq, :accountId, :currency, :amount)
                """, batch);
    }

    /**
     * Rehydrates the legs, converting the stored signed amount back into the
     * direction-plus-magnitude pair the API speaks in.
     */
    public List<Transfer.Leg> findLegs(UUID transferId) {
        return jdbc.query("""
                SELECT l.account_id, a.code, l.currency, l.amount_minor
                  FROM transfer_leg l
                  JOIN ledger_account a ON a.id = l.account_id
                 WHERE l.transfer_id = :id
                 ORDER BY l.seq
                """, Map.of("id", transferId), (rs, n) -> {
            long signed = rs.getLong("amount_minor");
            return new Transfer.Leg(
                    rs.getObject("account_id", UUID.class),
                    rs.getString("code"),
                    rs.getString("currency").trim(),
                    signed > 0 ? Direction.DEBIT : Direction.CREDIT,
                    Math.abs(signed));
        });
    }

    private static Timestamp timestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
