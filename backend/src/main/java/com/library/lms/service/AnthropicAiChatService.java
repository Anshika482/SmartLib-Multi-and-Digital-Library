package com.library.lms.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.library.lms.dto.ChatTurn;
import com.library.lms.dto.ChatRole;
import com.library.lms.exception.AiChatUnavailableException;

/**
 * The assistant, answered by Claude.
 *
 * <p><b>What is sent, and only this:</b> a system prompt naming the library and
 * the caller's role, and the caller's own question. No username, no email, no
 * account id, no token, no password or hash, and nothing about any other member
 * - there is no code path here that could reach one. The role travels because
 * it changes what an answer should say; the library name because an answer that
 * cannot name the library is not much of an answer.</p>
 *
 * <p><b>The key is never here.</b> It is read from configuration into the SDK
 * client once, at startup, and this class holds only the client. Nothing logs
 * it, returns it, or writes it anywhere.</p>
 *
 * <p><b>The model is told what it may not do.</b> The system prompt forbids
 * inventing library facts it was not given - hours, fines owed, who has a book -
 * and tells it to decline instead. A model with no data cannot leak any, but it
 * can make something up, and a confident invention about a member's fine is its
 * own kind of harm.</p>
 *
 * <p><b>Every failure is the same failure to a caller.</b> A timeout, a refused
 * key, a rate limit, a 500 and an answer with no text all become
 * {@link AiChatUnavailableException}, which is a 503 that says the assistant is
 * unavailable and nothing else. The provider's own message may quote the
 * request back or describe the account behind the key; only the exception's
 * type is logged.</p>
 */
public class AnthropicAiChatService implements AiChatService {

    static final String NAME = "anthropic";

    /**
     * Short answers, so a low ceiling. A library question wants a paragraph,
     * and a cap this size cannot be reached by an answer worth reading.
     */
    static final long MAX_TOKENS = 1024L;

    private static final Logger log = LoggerFactory.getLogger(AnthropicAiChatService.class);

    private final AnthropicClient client;

    private final String model;

    public AnthropicAiChatService(AnthropicClient client, String model) {
        this.client = client;
        this.model = model == null || model.isBlank() ? "claude-opus-5" : model.trim();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String reply(String message, ChatContext context) {
        // The client is built only where a key exists, so a null one means the
        // wiring is wrong rather than the provider being down - either way a
        // caller gets the same 503 and no detail.
        if (client == null || message == null || message.isBlank() || context == null) {
            throw new AiChatUnavailableException();
        }

        Message answer;
        try {
            answer = client.messages().create(params(message, context));
        } catch (AnthropicServiceException failure) {
            // The type only. A provider's error text can quote the request back
            // and describe the account the key belongs to; neither belongs in
            // this application's log or in anyone's 503.
            log.error("The assistant's provider refused a request ({})", failure.getClass().getSimpleName());
            throw new AiChatUnavailableException();
        } catch (RuntimeException failure) {
            // Timeouts and connection failures arrive as the SDK's own
            // exceptions, and anything else a client library decides to throw
            // must not escape into the request either.
            log.error("The assistant's provider could not be reached ({})", failure.getClass().getSimpleName());
            throw new AiChatUnavailableException();
        }

        return text(answer);
    }

    /**
     * The request: a system prompt, the question, and a short answer.
     *
     * <p>Effort is low because this is a chat route answering library questions
     * rather than a reasoning task, and low effort keeps a member waiting the
     * shortest time. Thinking is left at its default.</p>
     */
    MessageCreateParams params(String message, ChatContext context) {
        MessageCreateParams.Builder request = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .system(systemPrompt(context));

        // The conversation so far, as conversation - user and assistant turns in
        // the slots the API has for them, rather than pasted into the system
        // prompt. That is the structural half of the injection defence: an
        // earlier turn occupies a place the model already treats as somebody
        // talking, not as the rules it was given. The rules are the system
        // prompt, which is the server's and is rebuilt on every request.
        //
        // Already bounded and scrubbed by ConversationHistory before it reached
        // this context, and already oldest first.
        for (ChatTurn turn : context.history()) {
            if (turn.role() == ChatRole.ASSISTANT) {
                request.addAssistantMessage(turn.message());
            } else {
                request.addUserMessage(turn.message());
            }
        }

        return request.addUserMessage(message).build();
    }

    /**
     * What the model is told about where it is answering from.
     *
     * <p>Built by {@link AssistantPrompt}, which both providers share, so the
     * privacy rules and the catalogue fence cannot drift apart between them.
     * Kept here as a method because it is this class's prompt that the tests
     * around it assert.</p>
     */
    static String systemPrompt(ChatContext context) {
        return AssistantPrompt.systemPrompt(context);
    }

    /** The answer's text, or a failure if the provider sent none. */
    private String text(Message answer) {
        if (answer == null) {
            log.error("The assistant's provider returned nothing");
            throw new AiChatUnavailableException();
        }

        List<ContentBlock> content = answer.content();
        StringBuilder reply = new StringBuilder();
        if (content != null) {
            for (ContentBlock block : content) {
                block.text().ifPresent(text -> reply.append(text.text()));
            }
        }

        if (reply.isEmpty()) {
            // An answer with no text at all - a refusal, a stop before any was
            // produced, or a shape this code does not understand. Treated as a
            // failure rather than handed on as an empty reply.
            log.error("The assistant's provider returned an answer with no text");
            throw new AiChatUnavailableException();
        }

        return reply.toString().trim();
    }
}
