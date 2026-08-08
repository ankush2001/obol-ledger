package dev.ankush.obol.repo;

import dev.ankush.obol.domain.BalanceView;
import dev.ankush.obol.domain.Enums.NormalSide;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class BalanceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BalanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BalanceView> MAPPER = (rs, n) -> new BalanceView(
            rs.getObject("account_id", UUID.class),
            rs.getString("code"),
            rs.getString("currency").trim(),
            NormalSide.fromCode(rs.getString("normal_side")),
            rs.getBoolean("allow_negative"),
            rs.getLong("posted_minor"),
            rs.getLong("pending_debits_minor"),
            rs.getLong("pending_credits_minor"),
            rs.getLong("version"));

    private static final String SELECT = """
            SELECT b.account_id, a.code, a.currency, a.normal_side, a.allow_negative,
                   b.posted_minor, b.pending_debits_minor, b.pending_credits_minor, b.version
              FROM account_balance b
              JOIN ledger_account a ON a.id = b.account_id
            """;

    /**
     * Locks every account a transfer touches, <em>in ascending id order</em>,
     * and returns their balances.
     *
     * <p>The ordering is the entire point. Two concurrent transfers between
     * the same pair of accounts in opposite directions -- A pays B while B
     * pays A -- will deadlock if each locks its own payer first: each holds
     * what the other needs. Sorting the ids means every transaction in the
     * system reaches for those rows in the same sequence, so one simply waits
     * for the other. A global order over the contended resources is the
     * cheapest deadlock prevention there is, and it costs one sort.
     *
     * <p>Returned keyed by account id and lock-ordered, so callers cannot
     * accidentally reintroduce the problem by iterating in request order.
     */
    public Map<UUID, BalanceView> lockAll(Collection<UUID> accountIds) {
        SortedSet<UUID> ordered = new TreeSet<>(accountIds);
        List<BalanceView> rows = jdbc.query(SELECT + """
                 WHERE b.account_id IN (:ids)
                 ORDER BY b.account_id
                 FOR UPDATE OF b
                """, Map.of("ids", ordered), MAPPER);

        return rows.stream().collect(Collectors.toMap(
                BalanceView::accountId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /** Unlocked read, for the balance endpoint. */
    public Optional<BalanceView> find(UUID accountId) {
        return jdbc.query(SELECT + " WHERE b.account_id = :id",
                Map.of("id", accountId), MAPPER).stream().findFirst();
    }

    /**
     * Applies balance deltas for one transfer in a single batched round trip.
     *
     * <p>Written as deltas rather than absolute values on purpose: the
     * arithmetic happens inside the row that is already locked, so there is no
     * window between reading a balance and writing it back. {@code version} is
     * bumped for the benefit of any future read-modify-write path that takes
     * no lock.
     */
    public void applyDeltas(List<BalanceDelta> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        // Sorted for the same reason lockAll sorts: a caller that skipped the
        // lock step must still touch rows in the canonical order.
        List<BalanceDelta> ordered = deltas.stream()
                .sorted((a, b) -> a.accountId().compareTo(b.accountId()))
                .toList();

        // Built by hand rather than via BeanPropertySqlParameterSource: these
        // are records, whose accessors are postedDelta() not getPostedDelta(),
        // and the bean-conventions binder silently sees no properties at all.
        SqlParameterSource[] batch = ordered.stream()
                .map(d -> (SqlParameterSource) new MapSqlParameterSource()
                        .addValue("accountId", d.accountId())
                        .addValue("postedDelta", d.postedDelta())
                        .addValue("pendingDebitDelta", d.pendingDebitDelta())
                        .addValue("pendingCreditDelta", d.pendingCreditDelta()))
                .toArray(SqlParameterSource[]::new);

        jdbc.batchUpdate("""
                UPDATE account_balance
                   SET posted_minor          = posted_minor          + :postedDelta,
                       pending_debits_minor  = pending_debits_minor  + :pendingDebitDelta,
                       pending_credits_minor = pending_credits_minor + :pendingCreditDelta,
                       version               = version + 1,
                       updated_at            = now()
                 WHERE account_id = :accountId
                """, batch);
    }

    /**
     * Recomputes every account's settled balance directly from the postings
     * and reports rows where the stored projection disagrees.
     *
     * <p>This is the audit that makes the cached balance safe to trust: the
     * postings are the truth, {@code account_balance} is a convenience, and
     * this query proves they still agree. It is exposed over the admin API and
     * asserted in the concurrency tests.
     */
    public List<BalanceDrift> findDrift() {
        return jdbc.query("""
                SELECT a.id AS account_id,
                       a.code,
                       b.posted_minor                  AS stored_minor,
                       COALESCE(p.recomputed, 0)       AS recomputed_minor
                  FROM ledger_account a
                  JOIN account_balance b ON b.account_id = a.id
                  LEFT JOIN (
                        SELECT account_id, SUM(amount_minor) AS recomputed
                          FROM posting
                         GROUP BY account_id
                  ) p ON p.account_id = a.id
                 WHERE b.posted_minor <> COALESCE(p.recomputed, 0)
                 ORDER BY a.code
                """, (rs, n) -> new BalanceDrift(
                rs.getObject("account_id", UUID.class),
                rs.getString("code"),
                rs.getLong("stored_minor"),
                rs.getLong("recomputed_minor")));
    }

    /**
     * A balance adjustment for one account. Field names are read reflectively
     * by the batch update above, so they must match the SQL parameters.
     */
    public record BalanceDelta(
            UUID accountId,
            long postedDelta,
            long pendingDebitDelta,
            long pendingCreditDelta
    ) {
        public static BalanceDelta settled(UUID accountId, long signedMinor) {
            return new BalanceDelta(accountId, signedMinor, 0, 0);
        }

        /** Reserve (sign +1) or release (sign -1) a pending leg. */
        public static BalanceDelta reservation(UUID accountId, long signedMinor, int sign) {
            long magnitude = Math.abs(signedMinor) * sign;
            return signedMinor > 0
                    ? new BalanceDelta(accountId, 0, magnitude, 0)
                    : new BalanceDelta(accountId, 0, 0, magnitude);
        }
    }

    public record BalanceDrift(UUID accountId, String code, long storedMinor, long recomputedMinor) {
        public long differenceMinor() {
            return storedMinor - recomputedMinor;
        }
    }
}
