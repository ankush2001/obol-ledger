package dev.ankush.obol.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class LedgerConfig {

    /**
     * Time is injected, never read from a static.
     *
     * <p>Pending-transfer expiry is a rule about the clock, and a rule about
     * the clock that can only be tested by sleeping is a rule that will not be
     * tested. With this bean the expiry tests move time forward instead of
     * waiting for it.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The relay holds a database row lock while it posts, so these timeouts are
     * not a nicety -- they bound how long an unresponsive consumer can pin
     * outbox rows.
     */
    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${obol.http.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${obol.http.read-timeout:PT5S}") Duration readTimeout) {

        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings));
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("obol-ledger")
                .version("1.0.0")
                .description("""
                        A double-entry ledger and settlement engine.

                        Every transfer is a set of legs that must sum to zero; that rule is
                        enforced by a deferred constraint trigger in Postgres, not by this
                        service, so an unbalanced transfer cannot be committed even by a
                        client with direct database access.

                        Write endpoints accept an `Idempotency-Key` header. Retrying with the
                        same key returns the original response without moving money again;
                        reusing a key with a different body is rejected.
                        """)
                .license(new License().name("MIT")));
    }
}
