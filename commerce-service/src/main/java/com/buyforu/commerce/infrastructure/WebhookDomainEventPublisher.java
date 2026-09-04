package com.buyforu.commerce.infrastructure;

import com.buyforu.commerce.application.DomainEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/** 生产环境事件发布器：使用 HMAC-SHA256 签名，失败由 OutboxDispatcher 负责重试。 */
@Component
@Profile("production")
public class WebhookDomainEventPublisher implements DomainEventPublisher {
    private final RestClient client;
    private final byte[] signingSecret;

    public WebhookDomainEventPublisher(RestClient.Builder builder,
                                       @Value("${buyforu.events.webhook-url}") String webhookUrl,
                                       @Value("${buyforu.events.signing-secret}") String signingSecret) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException("EVENT_WEBHOOK_URL is required in production");
        }
        if (signingSecret == null || signingSecret.length() < 32) {
            throw new IllegalStateException("EVENT_SIGNING_SECRET must contain at least 32 characters");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.baseUrl(webhookUrl).requestFactory(factory).build();
        this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void publish(DomainEvent event) {
        String body = event.payload();
        client.post()
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-BuyForU-Event-Id", event.eventId())
                .header("X-BuyForU-Event-Type", event.eventType())
                .header("X-BuyForU-Signature-SHA256", sign(body))
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("could not sign commerce event", exception);
        }
    }
}
