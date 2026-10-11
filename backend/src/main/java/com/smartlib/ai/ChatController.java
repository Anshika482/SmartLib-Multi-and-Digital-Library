package com.smartlib.ai;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.smartlib.common.web.ClientInfo;
import com.smartlib.security.CurrentUser;

import tools.jackson.databind.ObjectMapper;

@RestController
public class ChatController {

    private final ChatService chat;
    private final ConversationStore store;
    private final ObjectMapper mapper;

    public ChatController(ChatService chat, ConversationStore store, ObjectMapper mapper) {
        this.chat = chat;
        this.store = store;
        this.mapper = mapper;
    }

    public record ChatBody(@Size(max = 60) String conversationId,
                           @NotBlank(message = "Type a message") @Size(max = 2000, message = "Messages are limited to 2000 characters") String message) {
    }

    public record TranscriptMessage(Long id, String role, String content, Object books, Integer latencyMs, java.time.Instant createdAt) {
    }

    @PostMapping("/api/ai/chat")
    public SseEmitter chat(@Valid @RequestBody ChatBody body, HttpServletRequest request) {
        return chat.start(CurrentUser.optional().orElse(null), ClientInfo.ip(request), new ChatService.ChatRequest(body.conversationId(), body.message()));
    }

    @GetMapping("/api/public/ai/status")
    public ChatService.Status status() {
        return chat.status();
    }

    @GetMapping("/api/ai/conversations")
    public List<ConversationStore.ConversationSummary> conversations() {
        return store.list(CurrentUser.require().userId());
    }

    @GetMapping("/api/ai/conversations/{id}/messages")
    public List<TranscriptMessage> messages(@PathVariable String id) {
        return store.transcript(CurrentUser.require().userId(), id).stream().map(s -> {
            Object books = null;
            if (s.payload() != null) {
                Map<?, ?> payload = mapper.readValue(s.payload(), Map.class);
                books = payload.get("books");
            }
            return new TranscriptMessage(s.id(), s.role(), s.content(), books, s.latencyMs(), s.createdAt());
        }).toList();
    }

    @DeleteMapping("/api/ai/conversations/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        store.delete(CurrentUser.require().userId(), id);
        return ResponseEntity.noContent().build();
    }
}
