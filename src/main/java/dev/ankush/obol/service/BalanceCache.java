package dev.ankush.obol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ankush.obol.domain.BalanceView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cache-aside for account balances.
 *
 * <p>Balance reads dominate the traffic of any ledger -- every screen, every
 * pre-flight check, every retry asks for one -- while writes are comparatively
 * rare and always know exactly which accounts they touched. That asymmetry is
 * what makes caching worth doing here and invalidation cheap enough to be
 * exact rather than time-based.
 *
 * <p><strong>Every operation is failure-tolerant on purpose.</strong> Redis is
 * an optimisation, not a dependency: if it is unreachable the ledger must keep
 * settling payments from Postgres alone. A cache that can take the system down
 * with it has made availability worse, not better -- so every call here
 * swallows its exception, records it, and lets the caller fall through to the
 * database.
 */
@Component
public class BalanceCache {

    private static final Logger log = LoggerFactory.getLogger(BalanceCache.class);
    private static final String PREFIX = "obol:balance:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;
    private final boolean enabled;

    private final Counter hits;
    private final Counter misses;
    private final Counter failures;

    public BalanceCache(StringRedisTemplate redis,
                        ObjectMapper json,
                        MeterRegistry meters,
                        @Value("${obol.cache.balance-ttl:PT5M}") Duration ttl,
                        @Value("${obol.cache.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
        this.enabled = enabled;
        this.hits = Counter.builder("obol.balance.cache").tag("result", "hit").register(meters);
        this.misses = Counter.builder("obol.balance.cache").tag("result", "miss").register(meters);
        this.failures = Counter.builder("obol.balance.cache").tag("result", "error").register(meters);
    }

    public Optional<BalanceView> get(UUID accountId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(PREFIX + accountId);
            if (raw == null) {
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(json.readValue(raw, BalanceView.class));
        } catch (Exception e) {
            return degrade("read", e);
        }
    }

    public void put(BalanceView view) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIX + view.accountId(), json.writeValueAsString(view), ttl);
        } catch (Exception e) {
            degrade("write", e);
        }
    }

    /**
     * Drops the cached balances for the accounts a transfer just moved.
     *
     * <p>Called from inside the write transaction. That is early -- the
     * transaction could still roll back, leaving the cache empty when it did
     * not need to be -- and that is the right trade: an unnecessary miss costs
     * one query, while a stale balance served after a successful transfer is a
     * wrong answer about someone's money.
     */
    public void evict(Collection<UUID> accountIds) {
        if (!enabled || accountIds.isEmpty()) {
            return;
        }
        try {
            List<String> keys = accountIds.stream().map(id -> PREFIX + id).toList();
            redis.delete(keys);
        } catch (Exception e) {
            degrade("evict", e);
        }
    }

    private Optional<BalanceView> degrade(String operation, Exception e) {
        failures.increment();
        // WARN, not ERROR: the request is still going to be answered correctly
        // from Postgres. This is a degradation to watch, not an outage.
        log.warn("balance cache {} failed, falling through to the database: {}", operation, e.toString());
        return Optional.empty();
    }
}
