package io.codetriage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codetriage.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal Claude Messages API client. Deliberately dependency-free (JDK HTTP
 * client plus Jackson, which Spring already brings) so there is no SDK version
 * to keep in step.
 *
 * Swap in another provider by implementing {@link LlmClient}: a corpus built
 * from two different model families covers more of what students actually use.
 */
@Component
public class AnthropicClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_ATTEMPTS = 3;

    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public AnthropicClient(AppProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public boolean isConfigured() {
        return props.getLlm().isConfigured();
    }

    @Override
    public String modelName() {
        return props.getLlm().getModel();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, double temperature) {
        AppProperties.Llm cfg = props.getLlm();
        if (!cfg.isConfigured()) {
            throw new LlmException("ANTHROPIC_API_KEY is not set, so no reference solutions can be generated.");
        }

        String body = buildBody(cfg, systemPrompt, userPrompt, temperature);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.getBaseUrl().replaceAll("/+$", "") + "/v1/messages"))
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("content-type", "application/json")
                .header("x-api-key", cfg.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 200) {
                    return extractText(response.body());
                }
                // 429 rate limit, 529 overloaded, 5xx: worth another go.
                if (status == 429 || status == 529 || status >= 500) {
                    last = new LlmException("Claude API returned " + status + ": " + snippet(response.body()));
                    sleepBackoff(attempt);
                    continue;
                }
                // 400/401/403: our fault, retrying changes nothing.
                throw new LlmException("Claude API returned " + status + ": " + snippet(response.body()));

            } catch (java.io.IOException e) {
                last = new LlmException("Could not reach the Claude API: " + e.getMessage(), e);
                sleepBackoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Generation was interrupted.", e);
            }
        }
        throw last != null ? last : new LlmException("Claude API call failed.");
    }

    private String buildBody(AppProperties.Llm cfg, String systemPrompt,
                             String userPrompt, double temperature) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", cfg.getModel());
        root.put("max_tokens", cfg.getMaxTokens());
        // Omitted unless explicitly enabled: several models now reject the
        // parameter with a 400 rather than ignoring it.
        if (cfg.isSendTemperature()) {
            root.put("temperature", Math.max(0.0, Math.min(1.0, temperature)));
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.put("system", systemPrompt);
        }
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", userPrompt);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new LlmException("Could not build the request body.", e);
        }
    }

    private String extractText(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode content = root.path("content");
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            if (sb.isEmpty()) {
                throw new LlmException("Claude returned no text content.");
            }
            return sb.toString();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Could not parse the Claude API response.", e);
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Duration.ofSeconds((long) Math.pow(2, attempt)).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String snippet(String body) {
        if (body == null) return "(no body)";
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
