package dev.ankush.obol.repo;

import dev.ankush.obol.domain.Posting;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class PostingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PostingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Posting> MAPPER = (rs, n) -> new Posting(
            rs.getLong("id"),
            rs.getObject("transfer_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("currency").trim(),
            rs.getLong("amount_minor"),
            rs.getInt("seq"),
            rs.getLong("balance_after_minor"),
            rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS =
            "id, transfer_id, account_id, currency, amount_minor, seq, balance_after_minor, created_at";

    /**
     * Writes every leg of a transfer in a single batched round trip.
     *
     * <p>One statement rather than a loop of inserts, for the same reason a
     * settlement job uses set-based SQL instead of a row-by-row cursor: the
     * per-statement overhead is the dominant cost at this size, and the legs
     * are known up front so there is nothing to iterate for.
     */
    public void insertBatch(List<NewPosting> postings) {
        MapSqlParameterSource[] batch = postings.stream()
                .map(p -> new MapSqlParameterSource()
                        .addValue("transferId", p.transferId())
                        .addValue("accountId", p.accountId())
                        .addValue("currency", p.currency())
                        .addValue("amount", p.amountMinor())
                        .addValue("seq", p.seq())
                        .addValue("balanceAfter", p.balanceAfterMinor()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO posting (transfer_id, account_id, currency, amount_minor, seq, balance_after_minor)
                VALUES (:transferId, :accountId, :currency, :amount, :seq, :balanceAfter)
                """, batch);
    }

    public List<Posting> findByTransfer(UUID transferId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM posting WHERE transfer_id = :id ORDER BY seq",
                Map.of("id", transferId), MAPPER);
    }

    /**
     * An account statement, newest first. Served by the
     * {@code (account_id, id DESC)} index, so it stays a range scan however
     * deep the caller pages.
     */
    public List<Posting> findByAccount(UUID accountId, int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM posting
                 WHERE account_id = :id
                 ORDER BY id DESC
                 LIMIT :limit OFFSET :offset
                """, Map.of("id", accountId, "limit", limit, "offset", offset), MAPPER);
    }

    /**
     * The system-wide sum of every posting ever written, per currency.
     *
     * <p>In a correct double-entry ledger this is exactly zero, always,
     * because every transfer summed to zero on the way in. It is the cheapest
     * possible whole-ledger health check and the admin API exposes it.
     */
    public Map<String, Long> sumByCurrency() {
        return jdbc.query("SELECT currency, SUM(amount_minor) AS total FROM posting GROUP BY currency",
                (rs, n) -> Map.entry(rs.getString("currency").trim(), rs.getLong("total")))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public long count() {
        Long c = jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM posting", Long.class);
        return c == null ? 0 : c;
    }

    /** A posting about to be written; it has no id or timestamp yet. */
    public record NewPosting(
            UUID transferId,
            UUID accountId,
            String currency,
            long amountMinor,
            int seq,
            long balanceAfterMinor
    ) {
    }
}
