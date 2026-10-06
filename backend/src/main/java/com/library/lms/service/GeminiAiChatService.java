package com.library.lms.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.ThinkingLevel;
import com.library.lms.dto.ChatRole;
import com.library.lms.dto.ChatTurn;
import com.library.lms.exception.AiChatUnavailableException;

/**
 * The assistant, answered by Gemini through the Google Gen AI SDK.
 *
 * <p><b>The same assistant as the others, reached differently.</b> The rules,
 * the privacy constraints and the catalogue fence are {@link AssistantPrompt}'s
 * and are shared with the Anthropic provider; the catalogue and resource facts
 * were looked up and access-filtered by SmartLib before this class was called.
 * Nothing here decides what a caller may see, and nothing here can look
 * anything up: a {@link ChatContext} is all it is given.
 *
 * <p><b>What is sent, and only this:</b> the system instruction, the earlier
 * turns of this conversation as conversation, and the caller's current question.
 * No username, no email, no account id, no token, no password or hash, no loan,
 * no fine, no payment detail, and nothing about any other member. The library
 * name travels because an answer that cannot name the library is not much of an
 * answer, and the role travels because it changes what an answer should say -
 * neither identifies the person asking.
 *
 * <p><b>Earlier turns go in the conversation slots, not the instruction.</b>
 * The SDK has {@code user} and {@code model} roles, and a turn put in one of
 * them occupies a place the model already reads as somebody talking rather than
 * as the rules it was given. The rules are the system instruction, which is the
 * server's and is rebuilt on every request. That is the structural half of the
 * injection defence; {@link ConversationHistory} has already bounded and
 * scrubbed the turns before they arrive.
 *
 * <p><b>The key is never here.</b> It is read from configuration into the SDK
 * client once, at startup, and this class holds only the client. Nothing logs
 * it, returns it, or writes it anywhere.
 *
 * <p><b>Every failure is the same failure to a caller.</b> A timeout, a refused
 * key, a rate limit, a safety block, a 500 and an answer with no text all become
 * {@link AiChatUnavailableException}, which is a 503 saying the assistant is
 * unavailable and nothing else. A provider's error text can quote the request
 * back or describe the account behind the key, so only the exception's type is
 * logged - never its message.
 */
public class GeminiAiChatService implements AiChatService {

    static final String NAME = "gemini";

    /**
     * The model used when none is configured.
     *
     * <p>A lite model, chosen by measurement rather than by reputation. Asked the
     * same four questions with the same prompt, {@code gemini-3.8-flash} answered
     * in 7-8 seconds on average with one reply taking 22; this one answered in
     * 1.4 seconds on average and never took 2. The answers were as well grounded
     * either way, because the catalogue facts come from SmartLib and not from the
     * model - the work here is reading a handful of rows and phrasing three
     * sentences, which is what a lite model is for.
     *
     * <p>A deployment that wants the larger model back sets
     * {@code GEMINI_MODEL=gemini-3.8-flash} and
     * {@code GEMINI_THINKING_LEVEL=low} together: the levels the two accept do
     * not overlap, so moving one without the other is refused by the provider.
     *
     * <p>Public because {@code ChatConfig} names it as the configuration
     * default, and one constant read from both places cannot drift the way two
     * copies of the same string would.</p>
     */
    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";

    /**
     * The ceiling on everything the model produces.
     *
     * <p><b>Do not lower this to make answers shorter.</b> It is a hard cutoff
     * over thinking tokens and answer tokens together, so a cap tightened to fit
     * three sentences can be spent entirely on thinking and return an answer
     * with no text in it - which this class turns into a 503. Length is asked
     * for in the prompt, which costs nothing; this is only a runaway guard.</p>
     */
    static final int MAX_OUTPUT_TOKENS = 1024;

    /**
     * How much the model deliberates before answering.
     *
     * <p><b>This is the setting that decides how long a caller waits.</b>
     * Thinking is on by default, and for a question answered from a handful of
     * catalogue rows it is nearly all of the latency: the tokens are produced
     * before any of the answer is, and they are charged for. The library
     * questions here are retrieval and phrasing, not reasoning.
     *
     * <p><b>The valid values depend on the model, and they do not overlap.</b>
     * {@code gemini-3.8-flash} accepts {@code low}, {@code medium} and
     * {@code high}; the {@code -flash-lite} models accept {@code minimal} and
     * {@code high}. So this is configurable rather than fixed, and the default
     * here is one {@link #DEFAULT_MODEL} accepts - change one and the other has
     * to change with it.
     *
     * <p><b>A level the model does not take is refused per request, not at
     * startup.</b> Measured: {@code high} against {@code gemini-3.8-flash} was
     * refused on every call, after 19 to 41 seconds of retrying, whatever
     * {@link #MAX_OUTPUT_TOKENS} was set to. Startup checks that the value is a
     * level at all; only the provider knows which of them a given model and key
     * will serve.</p>
     */
    public static final String DEFAULT_THINKING_LEVEL = "minimal";

    /** The levels the SDK knows, for rejecting a typo at startup. */
    public static final List<String> THINKING_LEVELS =
            List.of("minimal", "low", "medium", "high");

    /**
     * Low, because this answers library questions rather than writing prose.
     * The same question should get much the same answer twice.
     */
    static final float TEMPERATURE = 0.2f;

    /** The SDK's role for something the person said. */
    static final String USER_ROLE = "user";

    /** The SDK's role for something the assistant said. Gemini calls it "model". */
    static final String MODEL_ROLE = "model";

    private static final Logger log = LoggerFactory.getLogger(GeminiAiChatService.class);

    private final Client client;

    private final String model;

    private final ThinkingLevel thinkingLevel;

    public GeminiAiChatService(Client client, String model) {
        this(client, model, DEFAULT_THINKING_LEVEL);
    }

    public GeminiAiChatService(Client client, String model, String thinkingLevel) {
        this.client = client;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();

        String level = thinkingLevel == null || thinkingLevel.isBlank()
                ? DEFAULT_THINKING_LEVEL
                : thinkingLevel.trim().toLowerCase(Locale.ROOT);
        this.thinkingLevel = new ThinkingLevel(level);
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

        GenerateContentResponse answer;
        long startedAt = System.nanoTime();
        try {
            answer = client.models.generateContent(model, contents(message, context), config(context));
        } catch (ApiException failure) {
            // The type only. A provider's error text can quote the request back
            // and describe the account the key belongs to; neither belongs in
            // this application's log or in anyone's 503. This covers the key
            // being refused, the quota being spent and the provider's own 500s.
            log.error("The assistant's provider refused a request ({})", failure.getClass().getSimpleName());
            throw new AiChatUnavailableException();
        } catch (GenAiIOException failure) {
            log.error("The assistant's provider could not be reached ({})", failure.getClass().getSimpleName());
            throw new AiChatUnavailableException();
        } catch (RuntimeException failure) {
            // Anything else the client library decides to throw must not escape
            // into the request either. BaseException, the SDK's own root, is
            // package-private and cannot be named here - this catches it.
            log.error("The assistant's provider failed ({})", failure.getClass().getSimpleName());
            throw new AiChatUnavailableException();
        }

        // Numbers only, and never a word of the question or the answer. Without
        // them "the assistant is slow" cannot be told apart from "the model spent
        // four hundred tokens thinking", which is the difference between a
        // setting to change and a network to investigate.
        logTiming(answer, System.nanoTime() - startedAt);

        return text(answer);
    }

    /**
     * How long the provider took, and what it spent.
     *
     * <p>{@code thoughts} is the one worth watching: it is produced before any
     * of the answer and is what {@link #DEFAULT_THINKING_LEVEL} exists to
     * bound.</p>
     */
    private void logTiming(GenerateContentResponse answer, long elapsedNanos) {
        long millis = elapsedNanos / 1_000_000L;

        Integer prompt = null;
        Integer thoughts = null;
        Integer output = null;
        if (answer != null && answer.usageMetadata().isPresent()) {
            GenerateContentResponseUsageMetadata usage = answer.usageMetadata().get();
            prompt = usage.promptTokenCount().orElse(null);
            thoughts = usage.thoughtsTokenCount().orElse(null);
            output = usage.candidatesTokenCount().orElse(null);
        }

        log.info("Assistant answered in {}ms (model={} thinking={} promptTokens={} thoughtTokens={}"
                        + " answerTokens={})",
                millis, model, thinkingLevel, prompt, thoughts, output);
    }

    /**
     * The conversation as the SDK wants it: earlier turns oldest first, then the
     * question being asked.
     *
     * <p>Already bounded and scrubbed by {@link ConversationHistory} before it
     * reached this context, and already oldest first. A turn the assistant
     * produced becomes the {@code model} role, which is what stops the model
     * reading its own earlier words as something the person said.</p>
     */
    List<Content> contents(String message, ChatContext context) {
        List<Content> conversation = new ArrayList<>();

        for (ChatTurn turn : context.history()) {
            String role = turn.role() == ChatRole.ASSISTANT ? MODEL_ROLE : USER_ROLE;
            conversation.add(Content.builder()
                    .role(role)
                    .parts(Part.fromText(turn.message()))
                    .build());
        }

        // Last, because the question being answered is the latest turn rather
        // than one buried among the earlier ones.
        conversation.add(Content.builder()
                .role(USER_ROLE)
                .parts(Part.fromText(message))
                .build());

        return List.copyOf(conversation);
    }

    /**
     * The request's settings: the system instruction, a short answer, and one
     * candidate.
     *
     * <p>One candidate because only one is ever read, and asking for more would
     * be paid for and discarded.</p>
     */
    GenerateContentConfig config(ChatContext context) {
        return GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(AssistantPrompt.systemPrompt(context))))
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .temperature(TEMPERATURE)
                .candidateCount(1)
                .thinkingConfig(ThinkingConfig.builder()
                        .thinkingLevel(thinkingLevel)
                        // Asked for explicitly rather than left to the default.
                        // Thought summaries come back as parts of the answer, and
                        // text() concatenates the parts - so including them would
                        // put the model's working out in front of the person who
                        // asked a question about a book.
                        .includeThoughts(false)
                        .build())
                .build();
    }

    /** The thinking level this instance was built with, for the tests and the log. */
    String thinkingLevel() {
        return thinkingLevel.toString();
    }

    /** The answer's text, or a failure if the provider sent none. */
    private String text(GenerateContentResponse answer) {
        if (answer == null) {
            log.error("The assistant's provider returned nothing");
            throw new AiChatUnavailableException();
        }

        String reply;
        try {
            reply = answer.text();
        } catch (RuntimeException unreadable) {
            // text() reads through the first candidate's parts, so a response
            // that was blocked or stopped before any were produced can fail
            // here rather than return empty.
            log.error("The assistant's provider returned an answer that could not be read ({})",
                    unreadable.getClass().getSimpleName());
            throw new AiChatUnavailableException();
        }

        if (reply == null || reply.isBlank()) {
            // An answer with no text at all - a safety block, a stop before any
            // was produced, or a shape this code does not understand. Treated as
            // a failure rather than handed on as an empty reply.
            log.error("The assistant's provider returned an answer with no text");
            throw new AiChatUnavailableException();
        }

        return reply.trim();
    }
}
