package com.smartlib.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.smartlib.common.config.AppProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Streaming client for the Gemini REST API ({@code :streamGenerateContent?alt=sse}). Lines are pushed into a queue
 * and consumed with explicit first-chunk and total deadlines, so a slow upstream can never hang a request.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final Object END = new Object();

    private final AppProperties.Gemini config;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final AtomicBoolean thinkingUnsupported = new AtomicBoolean(false);

    public GeminiClient(AppProperties properties, ObjectMapper mapper) {
        this.config = properties.gemini();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
    }

    public boolean configured() {
        return config.configured();
    }

    public String model() {
        return config.model();
    }

    public interface Listener {
        void onText(String delta);
    }

    /** One model turn: streamed text plus any function calls (raw parts are kept to echo thought signatures back). */
    public record Turn(String text, List<Map<String, Object>> functionCalls, List<Map<String, Object>> modelParts, String finishReason,
                       boolean blocked) {
    }

    public static class GeminiException extends RuntimeException {
        private final int status;
        private final boolean timeout;
        private final boolean partialOutput;

        public GeminiException(int status, String message, boolean timeout, boolean partialOutput) {
            super(message);
            this.status = status;
            this.timeout = timeout;
            this.partialOutput = partialOutput;
        }

        public int status() {
            return status;
        }

        public boolean timeout() {
            return timeout;
        }

        public boolean partialOutput() {
            return partialOutput;
        }

        boolean retryable() {
            return !timeout && !partialOutput && (status == 0 || status == 429 || status >= 500);
        }
    }

    private record Failure(Throwable error) {
    }

    public Turn generate(String systemPrompt, List<Map<String, Object>> contents, List<Map<String, Object>> tools, Listener listener) {
        boolean withThinking = !thinkingUnsupported.get() && thinkingConfig() != null;
        try {
            return attempt(systemPrompt, contents, tools, listener, withThinking);
        } catch (GeminiException e) {
            // Models differ in which thinking setting they accept (and some answer a plain "invalid argument").
            // Retry once without it, and only remember that when the retry works.
            if (e.status() == 400 && withThinking) {
                Turn turn = attempt(systemPrompt, contents, tools, listener, false);
                thinkingUnsupported.set(true);
                log.info("Model {} rejected thinkingConfig {}; continuing without it", config.model(), thinkingConfig());
                return turn;
            }
            if (e.retryable()) {
                sleep(250 + ThreadLocalRandom.current().nextInt(400));
                return attempt(systemPrompt, contents, tools, listener, !thinkingUnsupported.get() && thinkingConfig() != null);
            }
            throw e;
        }
    }

    /** Minimal thinking keeps the first words fast: the level for Gemini 3+ models, else a token budget. */
    private Map<String, Object> thinkingConfig() {
        if (config.thinkingLevel() != null && !config.thinkingLevel().isBlank()) {
            return Map.of("thinkingLevel", config.thinkingLevel());
        }
        return config.thinkingBudget() == null ? null : Map.of("thinkingBudget", config.thinkingBudget());
    }

    private Turn attempt(String systemPrompt, List<Map<String, Object>> contents, List<Map<String, Object>> tools, Listener listener,
                         boolean withThinking) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", contents);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", List.of(Map.of("functionDeclarations", tools)));
            body.put("toolConfig", Map.of("functionCallingConfig", Map.of("mode", "AUTO")));
        }
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("temperature", config.temperature());
        generation.put("maxOutputTokens", config.maxOutputTokens());
        if (withThinking) {
            generation.put("thinkingConfig", thinkingConfig());
        }
        body.put("generationConfig", generation);

        String url = config.baseUrl() + "/v1beta/models/" + config.model() + ":streamGenerateContent?alt=sse";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("x-goog-api-key", config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        AtomicInteger status = new AtomicInteger();
        AtomicReference<String> errorBody = new AtomicReference<>();
        LineSubscriber lines = new LineSubscriber(queue);
        HttpResponse.BodyHandler<Void> handler = info -> {
            status.set(info.statusCode());
            if (info.statusCode() == 200) {
                return HttpResponse.BodySubscribers.fromLineSubscriber(lines);
            }
            return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), s -> {
                errorBody.set(s);
                queue.add(END);
                return null;
            });
        };
        CompletableFuture<HttpResponse<Void>> future = http.sendAsync(request, handler);
        future.whenComplete((r, t) -> {
            if (t != null) {
                queue.add(new Failure(t));
            }
        });

        long start = System.nanoTime();
        long firstDeadline = start + config.firstChunkTimeout().toNanos();
        long totalDeadline = start + config.totalTimeout().toNanos();
        boolean gotData = false;
        boolean blocked = false;
        String finish = null;
        String textSignature = null;
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> calls = new ArrayList<>();
        try {
            while (true) {
                long deadline = gotData ? totalDeadline : Math.min(firstDeadline, totalDeadline);
                long wait = deadline - System.nanoTime();
                Object item = wait <= 0 ? null : queue.poll(wait, TimeUnit.NANOSECONDS);
                if (item == null) {
                    throw new GeminiException(0, "The AI assistant took too long to respond.", true, !text.isEmpty());
                }
                if (item == END) {
                    break;
                }
                if (item instanceof Failure f) {
                    Throwable cause = f.error().getCause() == null ? f.error() : f.error().getCause();
                    throw new GeminiException(0, "Network error: " + cause.getClass().getSimpleName(), false, !text.isEmpty());
                }
                String line = (String) item;
                if (!line.startsWith("data:")) {
                    continue;
                }
                String json = line.substring(5).trim();
                if (json.isEmpty() || json.equals("[DONE]")) {
                    continue;
                }
                gotData = true;
                JsonNode node = mapper.readTree(json);
                if (node.has("error")) {
                    throw new GeminiException(node.path("error").path("code").asInt(500), node.path("error").path("message").asString(""),
                            false, !text.isEmpty());
                }
                if (node.path("promptFeedback").has("blockReason")) {
                    blocked = true;
                }
                JsonNode candidate = node.path("candidates").path(0);
                String reason = candidate.path("finishReason").asString(null);
                if (reason != null) {
                    finish = reason;
                }
                for (JsonNode part : candidate.path("content").path("parts")) {
                    if (part.path("thought").asBoolean(false)) {
                        continue;
                    }
                    if (part.has("functionCall")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> raw = mapper.treeToValue(part, Map.class);
                        calls.add(raw);
                    } else if (part.has("text")) {
                        String delta = part.path("text").asString("");
                        if (part.has("thoughtSignature")) {
                            textSignature = part.path("thoughtSignature").asString();
                        }
                        if (!delta.isEmpty()) {
                            text.append(delta);
                            listener.onText(delta);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiException(0, "Interrupted", true, !text.isEmpty());
        } finally {
            lines.cancel();
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        if (status.get() != 200) {
            throw new GeminiException(status.get(), errorMessage(errorBody.get()), false, false);
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        if (!text.isEmpty()) {
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("text", text.toString());
            if (textSignature != null) {
                textPart.put("thoughtSignature", textSignature);
            }
            parts.add(textPart);
        }
        parts.addAll(calls);
        if ("SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish)) {
            blocked = true;
        }
        return new Turn(text.toString(), calls, parts, finish, blocked);
    }

    private String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "Upstream error";
        }
        try {
            JsonNode n = mapper.readTree(body);
            JsonNode e = n.isArray() ? n.path(0).path("error") : n.path("error");
            return e.path("message").asString("Upstream error");
        } catch (RuntimeException ex) {
            return "Upstream error";
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class LineSubscriber implements Flow.Subscriber<String> {
        private final LinkedBlockingQueue<Object> queue;
        private volatile Flow.Subscription subscription;

        LineSubscriber(LinkedBlockingQueue<Object> queue) {
            this.queue = queue;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String item) {
            queue.add(item);
        }

        @Override
        public void onError(Throwable t) {
            queue.add(new Failure(t));
        }

        @Override
        public void onComplete() {
            queue.add(END);
        }

        void cancel() {
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }
    }
}
