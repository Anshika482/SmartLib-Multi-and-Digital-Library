package com.library.lms.service;

import com.library.lms.dto.ChatTurn;
import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.library.lms.dto.ChatResponse;
import com.library.lms.exception.UserNotFoundException;

/**
 * Answering a caller's question, in their own library's context.
 *
 * <p><b>A catalogue question is answered from the catalogue, not from the
 * model.</b> {@link BookIntelligenceService} recognises questions about titles,
 * authors, categories, availability and book details, and looks them up in the
 * caller's own library. The assistant is then asked to phrase what was found -
 * it is given the books and no way to look up any others.</p>
 *
 * <p><b>The context comes from the account, and it comes first.</b>
 * {@link ChatContextResolver} reads the caller's library, id and role from the
 * database and hands back a {@link ChatContext}; only then is the assistant
 * asked anything. An assistant therefore cannot be pointed at another library,
 * whatever a question says, because it is never told another library
 * exists.</p>
 *
 * <p><b>Deliberately not transactional.</b> The database work happens inside
 * the resolver's own transaction, which has committed and released its
 * connection before the assistant is called. That matters once the assistant is
 * a provider on the far side of the internet: a transaction held across that
 * call would pin a database connection for the length of a network round trip,
 * and a provider having a slow day would take the connection pool - and so the
 * rest of the application - with it. Nothing here writes, so there is nothing
 * for a transaction to protect.</p>
 *
 * <p><b>Every role may ask.</b> Members, librarians and administrators all
 * reach the assistant; what differs is what an implementation may tell them,
 * which is why the role travels in the context.</p>
 *
 * <p><b>The question is not written down.</b> It is passed to the assistant and
 * dropped: it is whatever the caller typed, which may be anything at all, and a
 * log line is the wrong place for it. The log records that an account asked
 * something, by id.</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AiChatService assistant;

    private final ChatContextResolver contextResolver;

    private final BookIntelligenceService bookIntelligence;

    public ChatService(AiChatService assistant, ChatContextResolver contextResolver,
            BookIntelligenceService bookIntelligence) {
        this.assistant = assistant;
        this.contextResolver = contextResolver;
        this.bookIntelligence = bookIntelligence;
    }

    /**
     * Answers one question for one signed-in caller.
     *
     * @param message               the question, validated at the boundary
     * @param authenticatedUsername the caller, from the security context
     * @return the answer, with the assistant that produced it
     * @throws UserNotFoundException if the authenticated name matches no account
     * @throws com.library.lms.exception.AiChatUnavailableException if the
     *         assistant's provider cannot answer
     */
    /**
     * A first question, with no conversation behind it.
     *
     * <p>The shape every question had before conversations existed, and still
     * the shape of the first one in any conversation. Delegates, so there is one
     * implementation rather than two.</p>
     */
    public ChatResponse reply(String message, String authenticatedUsername) {
        return reply(message, authenticatedUsername, List.of());
    }

    public ChatResponse reply(String message, String authenticatedUsername, List<ChatTurn> history) {
        // In a transaction, which ends when this returns. The caller's library
        // and role come from here and from nowhere else - never from the
        // conversation, which is why no amount of invented history can widen
        // what the lookup below is allowed to see.
        ChatContext context = contextResolver.resolve(authenticatedUsername);

        List<ChatTurn> conversation = ConversationHistory.trim(history);
        context = context.withHistory(conversation);

        // And a second one, if the question was about the catalogue. The
        // assistant is handed what this found; it never queries anything
        // itself, and it cannot ask for another library's shelves.
        // Whether disabled resources are visible follows the caller's role, as
        // it does at the resource API - taken from the account, never from the
        // question.
        context = lookUpCatalogue(message, conversation, context);

        // Out of every transaction. This may go over the network and take seconds.
        String reply = assistant.reply(message, context);

        // By id, and by length. Never the question, and never the answer: one
        // is the caller's own words and the other may quote them back.
        // By id, and by count. Never the question, never the answer, and never
        // a word of the conversation - one is the caller's own words and the
        // other may quote them back.
        log.info("Chat answered for user id={} in library id={} assistant='{}' catalogue={} turns={} replyLength={}",
                context.userId(), context.libraryId(), assistant.name(),
                context.hasCatalogue() ? context.catalogue().books().size() + " books" : "none",
                context.history().size(), reply == null ? 0 : reply.length());

        return new ChatResponse(reply, assistant.name(), LocalDateTime.now());
    }

    /**
     * Answers somebody who has not signed in.
     *
     * <p>Deliberately a separate method rather than a null username through the
     * one above. That one starts by resolving an account, and every branch
     * after it assumes a library; this one never has either, so the difference
     * is in the control flow where it can be read, not in a nullable argument
     * that has to be traced.</p>
     *
     * <p>What a visitor gets: the same assistant, the same intent detection,
     * and catalogue facts drawn from the public catalogue - titles, authors,
     * categories. What they cannot get, because it is never fetched: copy
     * counts, digital resources, and anything at all about members, loans,
     * fines or payments. The context handed to the provider has a null library
     * id, a null user id and a null role, so there is nothing of that kind in
     * the prompt to begin with.</p>
     *
     * <p>No database transaction is opened here at all: there is no account to
     * resolve, and the catalogue read is the provider-free part.</p>
     */
    /** A visitor's first question, with no conversation behind it. */
    public ChatResponse replyToVisitor(String message) {
        return replyToVisitor(message, List.of());
    }

    public ChatResponse replyToVisitor(String message, List<ChatTurn> history) {
        // Anonymous, whatever the conversation claims. A visitor who pastes
        // history saying they are a librarian is still answered from here, with
        // a null library, a null user and a null role - so there is no member
        // data in the prompt to begin with, and nothing to widen.
        ChatContext context = ChatContext.anonymous();

        List<ChatTurn> conversation = ConversationHistory.trim(history);
        context = context.withHistory(conversation);

        context = lookUpPublicCatalogue(message, conversation, context);

        // Outside any transaction, as above.
        String reply = assistant.reply(message, context);

        // No user id and no library id, because there are none. The shape of
        // this line is the proof: there is nothing to put in it.
        log.info("Chat answered for an anonymous visitor assistant='{}' catalogue={} turns={} replyLength={}",
                assistant.name(),
                context.hasCatalogue() ? context.catalogue().books().size() + " books" : "none",
                context.history().size(), reply == null ? 0 : reply.length());

        return new ChatResponse(reply, assistant.name(), LocalDateTime.now());
    }

    /**
     * The caller's own catalogue, for this question or for the one it refers to.
     *
     * <p>A follow-up names nothing - "do you have it?" - so when the question
     * alone finds nothing, the subject of the previous question is tried
     * instead. That is the whole of what makes a conversation work here, and it
     * changes only <i>what</i> is searched for: the library searched is still
     * the caller's, and the resources shown still follow their role.</p>
     */
    private ChatContext lookUpCatalogue(String message, List<ChatTurn> conversation, ChatContext context) {
        Optional<CatalogueLookup> found = bookIntelligence.lookup(
                message, context.libraryId(), context.isStaff());

        if (found.isEmpty()) {
            found = ConversationHistory.carriedSubject(message, conversation)
                    .flatMap(subject -> bookIntelligence.lookup(
                            subject, context.libraryId(), context.isStaff()));
        }

        return found.map(context::withCatalogue).orElse(context);
    }

    /** The same, over the public catalogue, for somebody who has not signed in. */
    private ChatContext lookUpPublicCatalogue(String message, List<ChatTurn> conversation, ChatContext context) {
        Optional<CatalogueLookup> found = bookIntelligence.publicLookup(message);

        if (found.isEmpty()) {
            found = ConversationHistory.carriedSubject(message, conversation)
                    .flatMap(bookIntelligence::publicLookup);
        }

        return found.map(context::withCatalogue).orElse(context);
    }
}
