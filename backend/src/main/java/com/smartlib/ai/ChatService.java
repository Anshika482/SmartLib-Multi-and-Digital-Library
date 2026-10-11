package com.smartlib.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.smartlib.catalogue.CatalogueDtos.CatalogueItem;
import com.smartlib.common.config.AppProperties;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.time.AppClock;
import com.smartlib.platform.PlatformSettings;
import com.smartlib.security.RateLimiter;
import com.smartlib.security.SmartLibPrincipal;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrates one assistant turn: history -> Gemini (streamed, with tool declarations) -> authorized tool calls
 * executed in parallel -> Gemini again with the real results -> streamed answer. Events sent to the browser:
 * meta, status, delta, books, done, error.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_TOOL_ROUNDS = 3;
    public static final String UNAVAILABLE_MESSAGE = "I'm having trouble connecting to the AI assistant right now. Please try again.";

    private final GeminiClient gemini;
    private final ChatTools tools;
    private final ConversationStore store;
    private final PlatformFacts facts;
    private final PlatformSettings settings;
    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper;
    private final AppProperties.Gemini config;
    private final AppClock clock;
    private final String promptTemplate;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(GeminiClient gemini, ChatTools tools, ConversationStore store, PlatformFacts facts, PlatformSettings settings,
                       RateLimiter rateLimiter, ObjectMapper mapper, AppProperties properties, AppClock clock) {
        this.gemini = gemini;
        this.tools = tools;
        this.store = store;
        this.facts = facts;
        this.settings = settings;
        this.rateLimiter = rateLimiter;
        this.mapper = mapper;
        this.config = properties.gemini();
        this.clock = clock;
        try (InputStream in = new ClassPathResource("ai/system-prompt.md").getInputStream()) {
            this.promptTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record ChatRequest(String conversationId, String message) {
    }

    public record Status(boolean enabled, boolean configured, String model) {
    }

    public Status status() {
        return new Status(settings.isEnabled(PlatformSettings.AI_ENABLED), gemini.configured(), gemini.configured() ? gemini.model() : null);
    }

    public SseEmitter start(SmartLibPrincipal principal, String ip, ChatRequest req) {
        String message = req.message() == null ? "" : req.message().trim();
        if (message.isEmpty() || message.length() > 2000) {
            throw ApiException.validation("message", "Message must be between 1 and 2000 characters");
        }
        if (principal != null) {
            rateLimiter.check("ai:user:" + principal.userId(), 20, Duration.ofMinutes(1));
        } else {
            rateLimiter.check("ai:ip:" + ip, 8, Duration.ofMinutes(1));
        }
        if (!settings.isEnabled(PlatformSettings.AI_ENABLED)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_DISABLED, "The AI assistant is currently turned off by the platform administrator.");
        }
        if (!gemini.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        ConversationStore.Handle handle = store.open(principal, req.conversationId(), message);
        SseEmitter emitter = new SseEmitter(config.totalTimeout().plusSeconds(20).toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(t -> closed.set(true));
        executor.submit(() -> run(principal, handle, message, emitter, closed));
        return emitter;
    }

    private void run(SmartLibPrincipal principal, ConversationStore.Handle handle, String message, SseEmitter emitter, AtomicBoolean closed) {
        long start = System.nanoTime();
        AtomicLong firstToken = new AtomicLong(-1);
        StringBuilder answer = new StringBuilder();
        List<CatalogueItem> cards = new ArrayList<>();
        List<ConversationStore.Stored> turn = new ArrayList<>();
        try {
            send(emitter, closed, "meta", Map.of("conversationId", handle.id(), "persistent", handle.persistent()));
            List<Map<String, Object>> contents = new ArrayList<>();
            for (ConversationStore.Stored s : store.history(handle, config.historyMessages())) {
                Map<String, Object> c = toContent(s);
                if (c != null) {
                    contents.add(c);
                }
            }
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", message))));
            turn.add(new ConversationStore.Stored("USER", message, null, null, null, null, null));

            String system = systemPrompt(principal);
            // Catalogue matches for the user's words, fetched up front (a few ms) so book questions skip the tool round-trips.
            ChatTools.Prefetch prefetch = prefetch(message);
            if (prefetch.found()) {
                system += "\n\nLive catalogue matches for the user's latest message (fetched just now, with per-library availability):\n"
                        + mapper.writeValueAsString(prefetch.response())
                        + "\nIf the question is about these books, answer directly from this data; do not call search_catalogue or "
                        + "get_book_availability for them. If the question is about something else, ignore this block.";
            }
            boolean blocked = false;
            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                List<Map<String, Object>> declarations = round < MAX_TOOL_ROUNDS ? tools.declarations() : List.of();
                GeminiClient.Turn result = gemini.generate(system, contents, declarations, delta -> {
                    firstToken.compareAndSet(-1, (System.nanoTime() - start) / 1_000_000);
                    answer.append(delta);
                    send(emitter, closed, "delta", Map.of("text", delta));
                });
                blocked |= result.blocked();
                if (result.functionCalls().isEmpty()) {
                    break;
                }
                contents.add(Map.of("role", "model", "parts", result.modelParts()));
                turn.add(new ConversationStore.Stored("TOOL_CALL", null, mapper.writeValueAsString(result.modelParts()), null, null, null, null));
                String label = tools.statusLabel(String.valueOf(((Map<?, ?>) result.functionCalls().get(0).get("functionCall")).get("name")));
                send(emitter, closed, "status", Map.of("message", label));

                List<Map<String, Object>> responses = executeTools(result.functionCalls(), principal, cards);
                if (!cards.isEmpty()) {
                    send(emitter, closed, "books", Map.of("books", dedupe(cards)));
                }
                contents.add(Map.of("role", "user", "parts", responses));
                turn.add(new ConversationStore.Stored("TOOL_RESULT", null, mapper.writeValueAsString(responses), null, null, null, null));
            }
            // Book cards for prefetched books the answer actually talks about.
            int before = dedupe(cards).size();
            String said = answer.toString().toLowerCase(Locale.ROOT);
            prefetch.books().stream().filter(b -> said.contains(mainTitle(b.title()))).forEach(cards::add);
            if (dedupe(cards).size() > before) {
                send(emitter, closed, "books", Map.of("books", dedupe(cards)));
            }
            if (answer.isEmpty()) {
                String fallback = blocked ? "I can't help with that request, but I'm happy to help with something else."
                        : "I couldn't come up with an answer for that. Could you rephrase your question?";
                answer.append(fallback);
                send(emitter, closed, "delta", Map.of("text", fallback));
            }
            long latency = (System.nanoTime() - start) / 1_000_000;
            Integer ttft = firstToken.get() < 0 ? null : (int) firstToken.get();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("books", dedupe(cards));
            turn.add(new ConversationStore.Stored("MODEL", answer.toString(), mapper.writeValueAsString(payload), (int) latency, ttft, null, null));
            Long messageId = store.appendTurn(handle, turn);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("messageId", messageId);
            done.put("latencyMs", latency);
            done.put("firstTokenMs", ttft);
            send(emitter, closed, "done", done);
            emitter.complete();
        } catch (ClientGone e) {
            emitter.complete();
        } catch (GeminiClient.GeminiException e) {
            log.warn("Gemini call failed (status={}, timeout={}): {}", e.status(), e.timeout(), e.getMessage());
            fail(emitter, closed, e.timeout() ? ErrorCodes.AI_TIMEOUT : ErrorCodes.AI_UNAVAILABLE,
                    e.timeout() ? "The AI assistant took too long to respond. Please try again." : UNAVAILABLE_MESSAGE, !answer.isEmpty());
        } catch (RuntimeException e) {
            log.error("Assistant turn failed", e);
            fail(emitter, closed, ErrorCodes.AI_UNAVAILABLE, UNAVAILABLE_MESSAGE, !answer.isEmpty());
        }
    }

    private ChatTools.Prefetch prefetch(String message) {
        try {
            return tools.prefetch(message);
        } catch (RuntimeException e) {
            log.warn("Catalogue prefetch failed: {}", e.toString());
            return ChatTools.Prefetch.NONE;
        }
    }

    /** "Clean Code: A Handbook…" → "clean code", used to tell whether the answer mentions a book. */
    private static String mainTitle(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        return (cut > 2 ? t.substring(0, cut) : t).trim();
    }

    private List<Map<String, Object>> executeTools(List<Map<String, Object>> calls, SmartLibPrincipal principal, List<CatalogueItem> cards) {
        List<Future<ChatTools.Outcome>> futures = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map<String, Object> part : calls) {
            Map<?, ?> call = (Map<?, ?>) part.get("functionCall");
            String name = String.valueOf(call.get("name"));
            @SuppressWarnings("unchecked")
            Map<String, Object> args = call.get("args") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            names.add(name);
            futures.add(executor.submit(() -> tools.execute(name, args, principal)));
        }
        List<Map<String, Object>> responses = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Map<String, Object> response;
            try {
                ChatTools.Outcome outcome = futures.get(i).get(5, TimeUnit.SECONDS);
                response = outcome.response();
                cards.addAll(outcome.cards());
            } catch (Exception e) {
                log.warn("Tool {} failed: {}", names.get(i), e.toString());
                response = Map.of("error", "TOOL_FAILED", "message", "SmartLib data is temporarily unavailable for this request.");
            }
            responses.add(Map.of("functionResponse", Map.of("name", names.get(i), "response", response)));
        }
        return responses;
    }

    private String systemPrompt(SmartLibPrincipal p) {
        String user;
        if (p == null) {
            user = "A visitor who is not signed in. They can browse and ask anything; personal tools (loans, fines, requests) require logging in.";
        } else {
            String first = p.fullName() == null ? "there" : p.fullName().trim().split("\\s+")[0];
            user = switch (p.role()) {
                case MEMBER -> "A signed-in member named " + first + ".";
                case LIBRARIAN, ADMIN -> "A signed-in " + p.role().name().toLowerCase() + " at " + p.libraryName() + " named " + first
                        + ". Personal loan/fine tools apply to member accounts only.";
                case SUPER_ADMIN -> "A signed-in platform super administrator named " + first + ".";
            };
        }
        return promptTemplate.replace("{{TODAY}}", LocalDate.now(AppClock.DEFAULT_ZONE).toString())
                .replace("{{USER}}", user)
                .replace("{{FACTS}}", facts.build());
    }

    private Map<String, Object> toContent(ConversationStore.Stored s) {
        try {
            return switch (s.role()) {
                case "USER" -> Map.of("role", "user", "parts", List.of(Map.of("text", s.content())));
                case "MODEL" -> s.content() == null || s.content().isBlank() ? null
                        : Map.of("role", "model", "parts", List.of(Map.of("text", s.content())));
                case "TOOL_CALL" -> Map.of("role", "model", "parts", mapper.readValue(s.payload(), new TypeReference<List<Map<String, Object>>>() {
                }));
                case "TOOL_RESULT" -> Map.of("role", "user", "parts", mapper.readValue(s.payload(), new TypeReference<List<Map<String, Object>>>() {
                }));
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<CatalogueItem> dedupe(List<CatalogueItem> cards) {
        Map<Long, CatalogueItem> unique = new LinkedHashMap<>();
        for (CatalogueItem c : cards) {
            unique.put(c.id(), c);
        }
        return new ArrayList<>(unique.values()).subList(0, Math.min(unique.size(), 8));
    }

    private static final class ClientGone extends RuntimeException {
        ClientGone() {
            super(null, null, false, false);
        }
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, Object data) {
        if (closed.get()) {
            throw new ClientGone();
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
            throw new ClientGone();
        }
    }

    private void fail(SseEmitter emitter, AtomicBoolean closed, String code, String message, boolean partial) {
        try {
            send(emitter, closed, "error", Map.of("code", code, "message", message, "partial", partial));
            emitter.complete();
        } catch (ClientGone ignored) {
            emitter.complete();
        }
    }

    public java.time.Instant now() {
        return clock.now();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
