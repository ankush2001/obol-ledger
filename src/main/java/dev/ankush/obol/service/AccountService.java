package dev.ankush.obol.service;

import dev.ankush.obol.domain.BalanceView;
import dev.ankush.obol.domain.Enums.AccountType;
import dev.ankush.obol.domain.Enums.NormalSide;
import dev.ankush.obol.domain.LedgerAccount;
import dev.ankush.obol.domain.Posting;
import dev.ankush.obol.error.LedgerException;
import dev.ankush.obol.repo.AccountRepository;
import dev.ankush.obol.repo.BalanceRepository;
import dev.ankush.obol.repo.PostingRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final BalanceRepository balances;
    private final PostingRepository postings;
    private final BalanceCache cache;
    private final Clock clock;

    public AccountService(AccountRepository accounts,
                          BalanceRepository balances,
                          PostingRepository postings,
                          BalanceCache cache,
                          Clock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.postings = postings;
        this.cache = cache;
        this.clock = clock;
    }

    @Transactional
    public LedgerAccount create(String code, String name, String currency,
                                AccountType type, boolean allowNegative) {
        LedgerAccount account = new LedgerAccount(
                UUID.randomUUID(), code, name, currency.toUpperCase(), type,
                normalSideFor(type), allowNegative, clock.instant());
        try {
            accounts.insert(account);
        } catch (DuplicateKeyException e) {
            // Let the unique index decide, rather than checking first: a
            // check-then-insert loses the race against a concurrent create.
            throw new LedgerException.DuplicateAccountCode(code);
        }
        return account;
    }

    /**
     * An account type determines its normal side; it is not a free choice.
     * Letting a caller declare an "asset that grows with credits" would put
     * every balance in the system at the mercy of a typo.
     */
    private static NormalSide normalSideFor(AccountType type) {
        return switch (type) {
            case ASSET, EXPENSE -> NormalSide.DEBIT;
            case LIABILITY, EQUITY, REVENUE -> NormalSide.CREDIT;
        };
    }

    @Transactional(readOnly = true)
    public LedgerAccount findByCode(String code) {
        return accounts.findByCode(code)
                .orElseThrow(() -> new LedgerException.AccountNotFound(code));
    }

    @Transactional(readOnly = true)
    public LedgerAccount findById(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new LedgerException.AccountNotFound(id.toString()));
    }

    @Transactional(readOnly = true)
    public List<LedgerAccount> findAllByCodes(Collection<String> codes) {
        return accounts.findAllByCodes(codes);
    }

    @Transactional(readOnly = true)
    public List<LedgerAccount> list(int limit, int offset) {
        return accounts.findAll(limit, offset);
    }

    /**
     * Reads a balance, consulting Redis first.
     *
     * <p>Cache-aside rather than write-through: the write path evicts instead
     * of updating, so there is no second place where a balance could be
     * computed -- and therefore no second place it could be computed wrongly.
     */
    @Transactional(readOnly = true)
    public BalanceView balanceOf(String accountCode) {
        LedgerAccount account = findByCode(accountCode);
        return cache.get(account.id()).orElseGet(() -> {
            BalanceView fresh = balances.find(account.id())
                    .orElseThrow(() -> new LedgerException.AccountNotFound(accountCode));
            cache.put(fresh);
            return fresh;
        });
    }

    /** Bypasses the cache. Used by the verification endpoint and its tests. */
    @Transactional(readOnly = true)
    public BalanceView balanceFromDatabase(String accountCode) {
        LedgerAccount account = findByCode(accountCode);
        return balances.find(account.id())
                .orElseThrow(() -> new LedgerException.AccountNotFound(accountCode));
    }

    /** The account's journal entries, newest first. */
    @Transactional(readOnly = true)
    public List<Posting> statement(String accountCode, int limit, int offset) {
        LedgerAccount account = findByCode(accountCode);
        return postings.findByAccount(account.id(), limit, offset);
    }
}
