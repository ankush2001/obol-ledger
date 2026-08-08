package dev.ankush.obol;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;

/**
 * Base for tests that need the real thing.
 *
 * <p>Postgres runs in a container rather than being swapped for H2. Almost
 * every guarantee this project makes lives in PostgreSQL-specific behaviour --
 * deferred constraint triggers, {@code FOR UPDATE SKIP LOCKED}, real row
 * locking under contention -- so a test against an in-memory substitute would
 * pass while proving nothing about what ships.
 *
 * <p>The containers are {@code static}, so one pair is started for the whole
 * suite and reused; each test clears the tables instead of paying to boot
 * Postgres again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    // Started once here rather than managed by @Testcontainers/@Container, and
    // never stopped.
    //
    // The JUnit extension stops static containers in afterAll -- per test
    // class. Spring, meanwhile, caches the application context across test
    // classes and keeps the connection pool pointed at the port the first
    // class's container had. The second class then inherits a live context
    // aimed at a container that no longer exists, and every query fails with
    // "connection refused" on a port nothing is listening to.
    //
    // Starting them in a static initialiser sidesteps that entirely: one pair
    // of containers for the whole JVM, matching the one cached context.
    // Testcontainers' Ryuk reaper removes them when the JVM exits, so nothing
    // is left running.
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @ServiceConnection
    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void quietBackgroundJobs(DynamicPropertyRegistry registry) {
        // The sweeper and the outbox relay are tested deliberately, by calling
        // them. Left on their timers they would fire mid-assertion and make
        // unrelated tests fail intermittently -- the classic way a suite
        // becomes something people rerun instead of trust.
        registry.add("obol.expiry.poll-interval", () -> "3600000");
        registry.add("obol.outbox.poll-interval", () -> "3600000");
        registry.add("obol.idempotency.purge-interval", () -> "3600000");
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void resetLedger() {
        // TRUNCATE rather than DELETE for the postings, and not only for speed:
        // the immutability trigger is a row-level BEFORE DELETE trigger, which
        // TRUNCATE does not fire. Deleting them one by one would hit the very
        // guard the production code relies on.
        jdbc.execute("TRUNCATE posting, transfer_leg, idempotency_key, outbox_event RESTART IDENTITY CASCADE");
        jdbc.execute("DELETE FROM transfer");
        jdbc.execute("DELETE FROM account_balance");
        jdbc.execute("DELETE FROM ledger_account");

        // Redis outlives the transaction rollback that Postgres gets, so a
        // balance cached by the previous test would be served to this one.
        Set<String> cached = redis.keys("obol:balance:*");
        if (cached != null && !cached.isEmpty()) {
            redis.delete(cached);
        }
    }
}
