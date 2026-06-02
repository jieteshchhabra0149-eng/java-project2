package com.chatapp.health;

import com.chatapp.config.GeminiProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class GeminiHealthIndicator implements HealthIndicator {

    private final GeminiProperties properties;
    private final RestTemplate restTemplate;

    public GeminiHealthIndicator(GeminiProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Override
    public Health health() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return Health.down().withDetail("reason", "GEMINI_API_KEY not set").build();
        }
        try {
            String url = properties.getApiBaseUrl() + "/models/" + properties.getModel()
                + "?key=" + properties.getApiKey();
            restTemplate.getForEntity(url, Map.class);
            return Health.up()
                .withDetail("model", properties.getModel())
                .build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
