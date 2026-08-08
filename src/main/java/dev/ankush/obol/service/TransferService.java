package dev.ankush.obol.service;

import dev.ankush.obol.domain.BalanceView;
import dev.ankush.obol.domain.Enums.Direction;
import dev.ankush.obol.domain.Enums.NormalSide;
import dev.ankush.obol.domain.Enums.TransferState;
import dev.ankush.obol.domain.LedgerAccount;
import dev.ankush.obol.domain.Transfer;
import dev.ankush.obol.error.LedgerException;
import dev.ankush.obol.outbox.LedgerEvent;
import dev.ankush.obol.repo.BalanceRepository;
import dev.ankush.obol.repo.BalanceRepository.BalanceDelta;
import dev.ankush.obol.repo.PostingRepository;
import dev.ankush.obol.repo.PostingRepository.NewPosting;
import dev.ankush.obol.repo.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything that moves money.
 *
 * <p>Three operations, and the invariant that survives all of them: the sum of
 * every posting in the ledger is zero, in every currency, at every instant an
 * outside observer could look.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountService accounts;
    private final TransferRepository transfers;
    private final PostingRepository postings;
    private final BalanceRepository balances;
    private final OutboxService outbox;
    private final BalanceCache balanceCache;
    private final Clock clock;

    public TransferService(AccountService accounts,
                           TransferRepository transfers,
                           PostingRepository postings,
                           BalanceRepository balances,
                           OutboxService outbox,
                           BalanceCache balanceCache,
                           Clock clock) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.postings = postings;
        this.balances = balances;
        this.outbox = outbox;
        this.balanceCache = balanceCache;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- create

    /**
     * Creates a transfer, settling it immediately or holding it pending.
     *
     * <p>Runs as one transaction from the first row lock to the outbox append.
     * Everything the caller is told happened, happened together.
     */
    @Transactional
    public Transfer create(TransferCommand cmd) {
        Instant now = clock.instant();

        List<Transfer.Leg> legs = resolveLegs(cmd);
        assertBalanced(legs);

        // Net movement per account. An account can legitimately appear on more
        // than one leg of the same transfer -- a fee account credited twice,
        // say -- and it is the net that decides whether it can afford this.
        Map<UUID, Long> netByAccount = netSignedByAccount(legs);

        // Lock first, decide second. Reading a balance and then acting on it
        // without the lock is the classic double-spend: two transfers both see
        // enough money and both proceed.
        Map<UUID, BalanceView> locked = balances.lockAll(netByAccount.keySet());
        assertSufficientFunds(netByAccount, locked);

        long debitTotal = legs.stream().filter(l -> l.direction() == Direction.DEBIT)
                .mapToLong(Transfer.Leg::amountMinor).sum();

        TransferState state = cmd.pending() ? TransferState.PENDING : TransferState.POSTED;
        Instant expiresAt = cmd.pending() ? now.plus(cmd.pendingTtl()) : null;

        Transfer transfer = new Transfer(
                UUID.randomUUID(), cmd.externalId(), state, cmd.currency(), debitTotal,
                cmd.description(), expiresAt, now,
                cmd.pending() ? null : now, null, legs);

        transfers.insert(transfer);
        transfers.insertLegs(transfer.id(), legs);

        if (cmd.pending()) {
            // Reserve, do not post. The money is spoken for but has not moved,
            // which is exactly what an authorisation means.
            balances.applyDeltas(reservationDeltas(netByAccount, +1));
            outbox.append(transfer, LedgerEvent.TRANSFER_PENDING, now);
        } else {
            writePostings(transfer, legs, locked);
            balances.applyDeltas(settlementDeltas(netByAccount));
            outbox.append(transfer, LedgerEvent.TRANSFER_POSTED, now);
        }

        balanceCache.evict(netByAccount.keySet());
        log.info("transfer {} {} {} {} across {} legs",
                transfer.id(), state, cmd.currency(), debitTotal, legs.size());
        return transfer;
    }

    // --------------------------------------------------------------- capture

    /** Settles a pending transfer: the reservation becomes real postings. */
    @Transactional
    public Transfer capture(UUID transferId) {
        Instant now = clock.instant();

        // FOR UPDATE, so two concurrent captures cannot both read PENDING.
        Transfer transfer = transfers.findByIdForUpdate(transferId)
                .orElseThrow(() -> new LedgerException.TransferNotFound(transferId.toString()));
        requirePending(transfer, "capture");

        List<Transfer.Leg> legs = transfers.findLegs(transferId);
        Map<UUID, Long> netByAccount = netSignedByAccount(legs);
        Map<UUID, BalanceView> locked = balances.lockAll(netByAccount.keySet());

        // No funds check here, deliberately. The reservation taken at
        // authorisation time already excluded this money from everyone else's
        // available balance, so re-checking could only ever fail spuriously --
        // and a capture that can fail for lack of funds defeats the purpose of
        // having authorised it in the first place.

        writePostings(transfer, legs, locked);

        List<BalanceDelta> deltas = new ArrayList<>(settlementDeltas(netByAccount));
        deltas.addAll(reservationDeltas(netByAccount, -1));
        balances.applyDeltas(deltas);

        // Compare-and-set. Zero rows means something else settled it between
        // the lock and here, which the lock should make impossible -- so if it
        // ever happens, fail loudly rather than post twice.
        if (transfers.markPosted(transferId, now) != 1) {
            throw new LedgerException.InvalidTransferState("capture", "concurrently modified");
        }

        Transfer settled = transfers.findById(transferId).orElseThrow();
        outbox.append(withLegs(settled, legs), LedgerEvent.TRANSFER_POSTED, now);
        balanceCache.evict(netByAccount.keySet());
        log.info("transfer {} captured", transferId);
        return withLegs(settled, legs);
    }

    // ------------------------------------------------------------------ void

    /** Releases a pending transfer without settling it. */
    @Transactional
    public Transfer voidTransfer(UUID transferId) {
        return release(transferId, TransferState.VOIDED, LedgerEvent.TRANSFER_VOIDED);
    }

    /** Same as a void, but performed by the sweeper rather than a caller. */
    @Transactional
    public Transfer expire(UUID transferId) {
        return release(transferId, TransferState.EXPIRED, LedgerEvent.TRANSFER_EXPIRED);
    }

    private Transfer release(UUID transferId, TransferState terminal, String eventType) {
        Instant now = clock.instant();

        Transfer transfer = transfers.findByIdForUpdate(transferId)
                .orElseThrow(() -> new LedgerException.TransferNotFound(transferId.toString()));
        requirePending(transfer, terminal == TransferState.EXPIRED ? "expire" : "void");

        List<Transfer.Leg> legs = transfers.findLegs(transferId);
        Map<UUID, Long> netByAccount = netSignedByAccount(legs);
        balances.lockAll(netByAccount.keySet());

        // Give the reserved funds back. No postings are written and none are
        // reversed, because nothing was ever posted -- which is precisely why
        // two-phase transfers are worth the extra state.
        balances.applyDeltas(reservationDeltas(netByAccount, -1));

        if (transfers.markVoided(transferId, now, terminal) != 1) {
            throw new LedgerException.InvalidTransferState("void", "concurrently modified");
        }

        Transfer released = transfers.findById(transferId).orElseThrow();
        outbox.append(withLegs(released, legs), eventType, now);
        balanceCache.evict(netByAccount.keySet());
        log.info("transfer {} {}", transferId, terminal);
        return withLegs(released, legs);
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public Transfer findById(UUID id) {
        Transfer t = transfers.findById(id)
                .orElseThrow(() -> new LedgerException.TransferNotFound(id.toString()));
        return withLegs(t, transfers.findLegs(id));
    }

    @Transactional(readOnly = true)
    public List<Transfer> findRecent(int limit, int offset) {
        return transfers.findRecent(limit, offset);
    }

    // --------------------------------------------------------------- helpers

    /**
     * Turns account codes into accounts and checks every leg belongs in this
     * transfer's currency. Resolved in one query rather than one per leg.
     */
    private List<Transfer.Leg> resolveLegs(TransferCommand cmd) {
        Set<String> codes = cmd.legs().stream()
                .map(TransferCommand.LegCommand::accountCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, LedgerAccount> byCode = accounts.findAllByCodes(codes).stream()
                .collect(Collectors.toMap(LedgerAccount::code, Function.identity()));

        List<Transfer.Leg> legs = new ArrayList<>(cmd.legs().size());
        for (TransferCommand.LegCommand leg : cmd.legs()) {
            LedgerAccount account = byCode.get(leg.accountCode());
            if (account == null) {
                throw new LedgerException.AccountNotFound(leg.accountCode());
            }
            if (!account.currency().equals(cmd.currency())) {
                throw new LedgerException.CurrencyMismatch(
                        account.code(), account.currency(), cmd.currency());
            }
            legs.add(new Transfer.Leg(
                    account.id(), account.code(), account.currency(), leg.direction(), leg.amountMinor()));
        }
        return legs;
    }

    /**
     * The application-level balance check.
     *
     * <p>The database enforces this too, at COMMIT. This exists so the caller
     * gets a 422 naming the imbalance instead of a 500 from a constraint
     * trigger -- the guarantee is the database's, the error message is ours.
     */
    private void assertBalanced(List<Transfer.Leg> legs) {
        long imbalance = legs.stream().mapToLong(Transfer.Leg::signedMinor).sum();
        if (imbalance != 0) {
            throw new LedgerException.UnbalancedTransfer(imbalance);
        }
    }

    private Map<UUID, Long> netSignedByAccount(List<Transfer.Leg> legs) {
        Map<UUID, Long> net = new LinkedHashMap<>();
        for (Transfer.Leg leg : legs) {
            net.merge(leg.accountId(), leg.signedMinor(), Long::sum);
        }
        return net;
    }

    private void assertSufficientFunds(Map<UUID, Long> netByAccount, Map<UUID, BalanceView> locked) {
        for (Map.Entry<UUID, Long> entry : netByAccount.entrySet()) {
            BalanceView balance = locked.get(entry.getKey());
            if (balance == null) {
                throw new LedgerException.AccountNotFound(entry.getKey().toString());
            }
            long outflow = outflowOf(balance.normalSide(), entry.getValue());
            if (outflow > 0 && !balance.canWithdraw(outflow)) {
                throw new LedgerException.InsufficientFunds(
                        balance.accountCode(), balance.availableMinor(), outflow);
            }
        }
    }

    /**
     * How much this net movement takes out of the account, in its own terms.
     *
     * <p>A credit drains a debit-normal account (cash paid out) and a debit
     * drains a credit-normal one (a customer spending their balance). Anything
     * flowing the other way is an inflow and needs no permission.
     */
    private static long outflowOf(NormalSide side, long netSigned) {
        return side == NormalSide.DEBIT ? Math.max(0, -netSigned) : Math.max(0, netSigned);
    }

    private List<BalanceDelta> settlementDeltas(Map<UUID, Long> netByAccount) {
        return netByAccount.entrySet().stream()
                .map(e -> BalanceDelta.settled(e.getKey(), e.getValue()))
                .toList();
    }

    /** {@code sign} is +1 to reserve at authorisation, -1 to give back. */
    private List<BalanceDelta> reservationDeltas(Map<UUID, Long> netByAccount, int sign) {
        return netByAccount.entrySet().stream()
                .map(e -> BalanceDelta.reservation(e.getKey(), e.getValue(), sign))
                .toList();
    }

    /**
     * Writes the journal entries, stamping each with the account's running
     * balance. Legs are applied in order so that a transfer touching one
     * account twice produces two entries whose running balances actually
     * follow one another.
     */
    private void writePostings(Transfer transfer, List<Transfer.Leg> legs, Map<UUID, BalanceView> locked) {
        Map<UUID, Long> running = new HashMap<>();
        List<NewPosting> rows = new ArrayList<>(legs.size());

        for (int seq = 0; seq < legs.size(); seq++) {
            Transfer.Leg leg = legs.get(seq);
            long before = running.computeIfAbsent(leg.accountId(),
                    id -> locked.get(id).postedMinor());
            long after = before + leg.signedMinor();
            running.put(leg.accountId(), after);

            rows.add(new NewPosting(
                    transfer.id(), leg.accountId(), leg.currency(), leg.signedMinor(), seq, after));
        }
        postings.insertBatch(rows);
    }

    private static void requirePending(Transfer transfer, String action) {
        if (transfer.state() != TransferState.PENDING) {
            throw new LedgerException.InvalidTransferState(action, transfer.state().name());
        }
    }

    private static Transfer withLegs(Transfer t, List<Transfer.Leg> legs) {
        return new Transfer(t.id(), t.externalId(), t.state(), t.currency(), t.amountMinor(),
                t.description(), t.pendingExpiresAt(), t.createdAt(), t.postedAt(), t.voidedAt(),
                legs.stream().sorted(Comparator.comparing(Transfer.Leg::accountCode)).toList());
    }
}
