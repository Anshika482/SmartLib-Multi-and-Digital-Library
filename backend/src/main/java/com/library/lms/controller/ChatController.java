package com.library.lms.controller;

import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.ChatRequest;
import com.library.lms.dto.ChatResponse;
import com.library.lms.service.ChatService;
import com.library.lms.service.PublicChatRateLimiter;

import jakarta.validation.Valid;

/**
 * Asking the assistant a question.
 *
 * <p>Open to every signed-in account - members, librarians and administrators -
 * and to nobody else. The caller's library is taken from their account by
 * {@link ChatService}, never from the request, so an answer is always about
 * their own library.</p>
 *
 * <p><b>Open to visitors as well.</b> With no authentication the question goes
 * to {@link ChatService#replyToVisitor}, which has no account to resolve and
 * draws catalogue facts from the public catalogue only. The two paths are
 * chosen here, by whether the filter chain established an identity - never by
 * anything in the request body.</p>
 *
 * <p>The visitor path is rate limited per client address; the signed-in path is
 * not, because a signed-in caller is already accountable and already had to get
 * past authentication to arrive.</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    private final PublicChatRateLimiter rateLimiter;

    public ChatController(ChatService chatService, PublicChatRateLimiter rateLimiter) {
        this.chatService = chatService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * POST /api/chat - answers one question, signed in or not.
     *
     * <p>Answers 200 with the reply. A missing, blank or over-long message is a
     * 400 from the usual validation handler. A visitor who has asked too often
     * gets a 429 with {@code Retry-After}, and the assistant is not reached.</p>
     */
    @PostMapping
    public ResponseEntity<ChatResponse> reply(@Valid @RequestBody ChatRequest request,
            Authentication authentication, HttpServletRequest httpRequest) {
        if (isSignedIn(authentication)) {
            return ResponseEntity.ok(chatService.reply(request.getMessage(), authentication.getName(), request.getHistory()));
        }

        String client = httpRequest.getRemoteAddr();

        if (!rateLimiter.tryAcquire(client)) {
            // 429 before the provider is called, so a refusal costs nothing.
            return ResponseEntity.status(429)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimiter.secondsUntilReset(client)))
                    .build();
        }

        return ResponseEntity.ok(chatService.replyToVisitor(request.getMessage(), request.getHistory()));
    }

    /**
     * Whether the filter chain established a real identity.
     *
     * <p>Spring Security supplies an {@code AnonymousAuthenticationToken} for an
     * unauthenticated request on a permitted path, and that token is
     * {@code isAuthenticated()}. Checking the type is what separates "signed in"
     * from "allowed through without signing in".</p>
     */
    private static boolean isSignedIn(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
