package dev.ankush.obol.outbox;

import dev.ankush.obol.repo.OutboxRepository;
import dev.ankush.obol.repo.OutboxRepository.OutboxRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.List;

/**
 * Delivers outbox events to whoever is listening.
 *
 * <p>Delivery is <b>at-least-once</b>. An event is only marked published after
 * the consumer has acknowledged it, so a crash mid-delivery re-sends rather
 * than loses -- and consumers are therefore required to deduplicate on
 * {@code transferId} plus {@code eventType}. The alternative, marking first,
 * would be at-most-once, and a reconciliation service that silently misses
 * transfers is worse than useless: it would report a clean ledger while money
 * went unaccounted for.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final RestClient http;
    private final Clock clock;
    private final String targetUrl;
    private final int batchSize;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(OutboxRepository outbox,
                       RestClient.Builder httpBuilder,
                       MeterRegistry meters,
                       Clock clock,
                       @Value("${obol.outbox.target-url:}") String targetUrl,
                       @Value("${obol.outbox.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.http = httpBuilder.build();
        this.clock = clock;
        this.targetUrl = targetUrl;
        this.batchSize = batchSize;
        this.published = Counter.builder("obol.outbox.events").tag("result", "published").register(meters);
        this.failed = Counter.builder("obol.outbox.events").tag("result", "failed").register(meters);
    }

    /**
     * One drain cycle.
     *
     * <p>The whole batch runs in a transaction, because the {@code FOR UPDATE
     * SKIP LOCKED} claim is only meaningful while it is held. That does mean a
     * row lock is held across an HTTP call, which is why the client is
     * configured with a short timeout and the batch is bounded -- an
     * unresponsive consumer should slow this relay down, not pin database rows
     * indefinitely.
     */
    @Scheduled(fixedDelayString = "${obol.outbox.poll-interval:2000}")
    @Transactional
    public void drain() {
        if (targetUrl.isBlank()) {
            return; // nothing subscribed; events accumulate and are visible in /admin/verify
        }

        List<OutboxRow> batch = outbox.claimUnpublished(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        List<Long> ids = batch.stream().map(OutboxRow::id).toList();

        // The payloads are already JSON, stored as written. Concatenating them
        // into an array beats deserialising and re-serialising: the consumer
        // receives byte-for-byte what the ledger recorded, so a disagreement
        // can never be introduced by this hop.
        String body = batch.stream()
                .map(OutboxRow::payload)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        try {
            http.post()
                    .uri(targetUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            outbox.markPublished(ids, clock.instant());
            published.increment(batch.size());
            log.debug("published {} outbox events", batch.size());
        } catch (Exception e) {
            // Left unpublished on purpose: the next cycle retries. attempts is
            // incremented so a permanently poisoned event is visible rather
            // than silently spinning forever.
            outbox.recordFailure(ids, e.toString());
            failed.increment(batch.size());
            log.warn("outbox delivery of {} events failed, will retry: {}", batch.size(), e.toString());
        }
    }
}
