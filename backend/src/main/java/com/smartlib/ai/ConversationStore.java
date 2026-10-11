package com.smartlib.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.util.Texts;
import com.smartlib.security.SmartLibPrincipal;

/**
 * Server-side conversation memory (the client only sends a conversation id + new message, so it cannot forge history
 * or tool results). Signed-in users are persisted; visitors get a 30-minute in-memory conversation.
 */
@Service
public class ConversationStore {

    public record Stored(String role, String content, String payload, Integer latencyMs, Integer firstTokenMs, Instant createdAt, Long id) {
    }

    public record Handle(String id, Long userId) {
        boolean persistent() {
            return userId != null;
        }
    }

    public record ConversationSummary(String id, String title, Instant createdAt, Instant lastMessageAt) {
    }

    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final Cache<String, List<Stored>> visitors = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(30)).maximumSize(20_000).build();

    public ConversationStore(AiConversationRepository conversations, AiMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @Transactional
    public Handle open(SmartLibPrincipal principal, String requestedId, String firstMessage) {
        if (principal != null) {
            if (requestedId != null && !requestedId.isBlank()) {
                AiConversation c = conversations.findByIdAndUserId(requestedId, principal.userId())
                        .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Conversation not found."));
                return new Handle(c.getId(), principal.userId());
            }
            AiConversation c = new AiConversation();
            c.setId(UUID.randomUUID().toString());
            c.setUserId(principal.userId());
            c.setTitle(Texts.truncate(firstMessage.replaceAll("\\s+", " ").trim(), 80));
            c.setCreatedAt(Instant.now());
            c.setLastMessageAt(Instant.now());
            conversations.save(c);
            return new Handle(c.getId(), principal.userId());
        }
        if (requestedId != null && visitors.getIfPresent(requestedId) != null) {
            return new Handle(requestedId, null);
        }
        String id = UUID.randomUUID().toString();
        visitors.put(id, Collections.synchronizedList(new ArrayList<>()));
        return new Handle(id, null);
    }

    /** Most recent messages, oldest first, always starting at a user message. */
    public List<Stored> history(Handle h, int limit) {
        List<Stored> out;
        if (h.persistent()) {
            List<AiMessage> rows = new ArrayList<>(messages.findByConversationIdOrderByIdDesc(h.id(), PageRequest.of(0, limit)));
            Collections.reverse(rows);
            out = rows.stream().map(ConversationStore::toStored).toList();
        } else {
            List<Stored> all = visitors.getIfPresent(h.id());
            if (all == null) {
                return List.of();
            }
            synchronized (all) {
                out = new ArrayList<>(all.subList(Math.max(0, all.size() - limit), all.size()));
            }
        }
        int start = 0;
        while (start < out.size() && !"USER".equals(out.get(start).role())) {
            start++;
        }
        return out.subList(start, out.size());
    }

    /** Persists one completed turn atomically; returns the id of the final model message (or null for visitors). */
    @Transactional
    public Long appendTurn(Handle h, List<Stored> turn) {
        Long lastId = null;
        if (h.persistent()) {
            for (Stored s : turn) {
                AiMessage m = new AiMessage();
                m.setConversationId(h.id());
                m.setRole(s.role());
                m.setContent(s.content());
                m.setPayload(s.payload());
                m.setLatencyMs(s.latencyMs());
                m.setFirstTokenMs(s.firstTokenMs());
                m.setCreatedAt(Instant.now());
                lastId = messages.save(m).getId();
            }
            conversations.findById(h.id()).ifPresent(c -> {
                c.setLastMessageAt(Instant.now());
                conversations.save(c);
            });
        } else {
            List<Stored> all = visitors.getIfPresent(h.id());
            if (all != null) {
                all.addAll(turn);
                while (all.size() > 60) {
                    all.remove(0);
                }
            }
        }
        return lastId;
    }

    public List<ConversationSummary> list(long userId) {
        return conversations.findTop30ByUserIdOrderByLastMessageAtDesc(userId).stream()
                .map(c -> new ConversationSummary(c.getId(), c.getTitle(), c.getCreatedAt(), c.getLastMessageAt())).toList();
    }

    public List<Stored> transcript(long userId, String conversationId) {
        conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Conversation not found."));
        return messages.findByConversationIdOrderByIdAsc(conversationId).stream()
                .filter(m -> "USER".equals(m.getRole()) || "MODEL".equals(m.getRole()))
                .map(ConversationStore::toStored).toList();
    }

    @Transactional
    public void delete(long userId, String conversationId) {
        AiConversation c = conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Conversation not found."));
        conversations.delete(c);
    }

    public void forgetVisitor(String id) {
        if (id != null) {
            visitors.invalidate(id);
        }
    }

    private static Stored toStored(AiMessage m) {
        return new Stored(m.getRole(), m.getContent(), m.getPayload(), m.getLatencyMs(), m.getFirstTokenMs(), m.getCreatedAt(), m.getId());
    }
}
