package dev.ankush.obol.repo;

import dev.ankush.obol.domain.Enums.AccountType;
import dev.ankush.obol.domain.Enums.NormalSide;
import dev.ankush.obol.domain.LedgerAccount;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<LedgerAccount> MAPPER = (rs, n) -> new LedgerAccount(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("currency").trim(),
            AccountType.valueOf(rs.getString("account_type")),
            NormalSide.fromCode(rs.getString("normal_side")),
            rs.getBoolean("allow_negative"),
            rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS =
            "id, code, name, currency, account_type, normal_side, allow_negative, created_at";

    /**
     * Creates the account and its balance row together. The two are never
     * separable -- an account without a balance row would fail the first
     * transfer that touched it with a null pointer rather than an honest
     * error -- so they are inserted in one call and one transaction.
     */
    public void insert(LedgerAccount a) {
        jdbc.update("""
                INSERT INTO ledger_account (id, code, name, currency, account_type, normal_side, allow_negative)
                VALUES (:id, :code, :name, :currency, :type, :side, :allowNegative)
                """, new MapSqlParameterSource()
                .addValue("id", a.id())
                .addValue("code", a.code())
                .addValue("name", a.name())
                .addValue("currency", a.currency())
                .addValue("type", a.type().name())
                .addValue("side", String.valueOf(a.normalSide().code()))
                .addValue("allowNegative", a.allowNegative()));

        jdbc.update("INSERT INTO account_balance (account_id) VALUES (:id)", Map.of("id", a.id()));
    }

    public Optional<LedgerAccount> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ledger_account WHERE id = :id",
                Map.of("id", id), MAPPER).stream().findFirst();
    }

    public Optional<LedgerAccount> findByCode(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ledger_account WHERE code = :code",
                Map.of("code", code), MAPPER).stream().findFirst();
    }

    public List<LedgerAccount> findAllByCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM ledger_account WHERE code IN (:codes)",
                Map.of("codes", codes), MAPPER);
    }

    public List<LedgerAccount> findAll(int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ledger_account ORDER BY code LIMIT :limit OFFSET :offset",
                Map.of("limit", limit, "offset", offset), MAPPER);
    }

    public boolean existsByCode(String code) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM ledger_account WHERE code = :code)",
                Map.of("code", code), Boolean.class);
        return Boolean.TRUE.equals(found);
    }
}
