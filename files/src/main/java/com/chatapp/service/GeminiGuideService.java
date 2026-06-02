package com.chatapp.service;

import com.chatapp.config.GeminiProperties;
import com.chatapp.model.AiHistoryTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

@Service
public class GeminiGuideService {

    private static final Logger log = LoggerFactory.getLogger(GeminiGuideService.class);

    private static final String SYSTEM_PROMPT = """
        You are NexusGuide, the built-in AI assistant for NexusChat.
        Help users with any topic: programming, Java, Spring Boot, multithreading, WebSockets,
        school subjects, career advice, debugging, system design, and general knowledge.
        Be clear, accurate, and encouraging. Use short paragraphs and bullet lists when helpful.
        For code, prefer Java examples when relevant to this app, but support any language asked.
        If unsure, say what you know and what to verify. Keep answers focused unless the user wants depth.
        """;

    private final GeminiProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public GeminiGuideService(GeminiProperties properties, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String ask(String userMessage, List<AiHistoryTurn> history, String topic) {
        if (userMessage == null || userMessage.isBlank()) {
            return "Please enter a question for NexusGuide.";
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return "AI guide is not configured. Set GEMINI_API_KEY.";
        }

        try {
            Map<String, Object> body = buildRequestBody(userMessage, history, topic);
            String url = properties.getApiBaseUrl() + "/models/" + properties.getModel()
                + ":generateContent?key=" + properties.getApiKey();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return parseReply(response.getBody());
        } catch (HttpStatusCodeException e) {
            log.error("Gemini API HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return parseHttpError(e);
        } catch (RestClientException e) {
            log.error("Gemini API request failed", e);
            return "NexusGuide could not reach the AI service. Check your API key and network, then try again.";
        } catch (Exception e) {
            log.error("Gemini guide error", e);
            return "NexusGuide hit an error processing your request. Please try again.";
        }
    }

    public void streamAsk(
        String userMessage,
        List<AiHistoryTurn> history,
        String topic,
        Consumer<String> onChunk,
        Runnable onComplete,
        Consumer<String> onError
    ) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            onError.accept("AI guide is not configured. Set GEMINI_API_KEY.");
            return;
        }
        try {
            Map<String, Object> body = buildRequestBody(userMessage, history, topic);
            String url = properties.getApiBaseUrl() + "/models/" + properties.getModel()
                + ":streamGenerateContent?alt=sse&key=" + properties.getApiKey();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream()
            );

            if (response.statusCode() >= 400) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                onError.accept(parseErrorJson(errBody));
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String json = line.substring(5).trim();
                    if (json.isEmpty() || "[DONE]".equals(json)) continue;
                    extractStreamText(json, onChunk);
                }
            }
            onComplete.run();
        } catch (Exception e) {
            log.error("Gemini stream error", e);
            onError.accept("NexusGuide streaming failed. Try again.");
        }
    }

    private void extractStreamText(String json, Consumer<String> onChunk) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            for (JsonNode part : parts) {
                if (part.has("text")) {
                    onChunk.accept(part.get("text").asText());
                }
            }
        } catch (Exception ignored) {
            // skip malformed SSE chunk
        }
    }

    private Map<String, Object> buildRequestBody(String userMessage, List<AiHistoryTurn> history, String topic) {
        Map<String, Object> body = new LinkedHashMap<>();
        String systemText = SYSTEM_PROMPT;
        if (topic != null && !topic.isBlank()) {
            systemText += "\nThe user is currently asking about: " + topic.trim() + ".";
        }
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemText))));

        List<Map<String, Object>> contents = new ArrayList<>();
        if (history != null) {
            for (AiHistoryTurn turn : history) {
                if (turn.getText() == null || turn.getText().isBlank()) continue;
                String role = "assistant".equalsIgnoreCase(turn.getRole()) ? "model" : "user";
                contents.add(Map.of("role", role, "parts", List.of(Map.of("text", turn.getText()))));
            }
        }
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", userMessage))));
        body.put("contents", contents);
        body.put("generationConfig", Map.of("temperature", 0.7, "maxOutputTokens", 2048));
        return body;
    }

    private String parseHttpError(HttpStatusCodeException e) {
        try {
            JsonNode root = objectMapper.readTree(e.getResponseBodyAsString());
            String msg = root.path("error").path("message").asText(e.getStatusText());
            if (e.getStatusCode().value() == 429) {
                return "NexusGuide is rate-limited right now. Wait a minute and try again.";
            }
            return "NexusGuide error: " + msg;
        } catch (Exception ex) {
            return "NexusGuide error: " + e.getStatusCode();
        }
    }

    private String parseErrorJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return root.path("error").path("message").asText("Unknown API error");
        } catch (Exception e) {
            return "NexusGuide API error";
        }
    }

    private String parseReply(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return "NexusGuide received an empty response.";
        }
        JsonNode root = objectMapper.readTree(json);
        if (root.has("error")) {
            return "NexusGuide error: " + root.path("error").path("message").asText("Unknown API error");
        }
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return "NexusGuide could not generate a reply. Try rephrasing your question.";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.has("text")) sb.append(part.get("text").asText());
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? "NexusGuide could not generate a reply." : text;
    }
}
