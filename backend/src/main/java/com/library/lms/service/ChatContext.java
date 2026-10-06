package com.library.lms.service;

import java.util.List;

import com.library.lms.dto.ChatTurn;
import com.library.lms.entity.Role;

/**
 * Who is asking, and from where.
 *
 * <p><b>Every field comes from the authenticated account</b>, never from the
 * request body. That is what makes library isolation hold for a chat answer the
 * way it holds for every query in this application: an implementation that
 * looks anything up can only be told one library, and it is the caller's own.
 * A caller cannot name a library, a member or a loan belonging to anyone
 * else.</p>
 *
 * <p><b>Nothing sensitive is here.</b> Ids, a role and a library name - no
 * password, no hash, no token, and no authority string the security layer
 * depends on. An implementation that one day sends this to a provider sends
 * nothing that would matter if it were read.</p>
 *
 * <p><b>The catalogue, when there is one to show.</b> A catalogue question is
 * answered from the caller's own library before any assistant is called, and
 * the result travels here as a {@link CatalogueLookup}. An assistant is given
 * those facts and no way to ask for more: it holds no repository, so it cannot
 * widen the search or reach another library's shelves.</p>
 *
 * @param libraryId   the caller's library, the only one an answer may draw on
 * @param libraryName that library's name, so an answer can say where it is from
 * @param userId      the caller, for answers about their own loans later
 * @param role        what the caller may be told
 * @param catalogue   what their library holds, when the question was about it
 */
public record ChatContext(Long libraryId, String libraryName, Long userId, Role role, CatalogueLookup catalogue,
        List<ChatTurn> history) {

    /**
     * The canonical form, with the conversation so far.
     *
     * <p>History travels on this record rather than as a separate argument
     * deliberately: this is already the one object that says everything the
     * assistant may know, and the scope it is allowed to know it in. Keeping the
     * two together means there is no way to hand a provider a conversation
     * without also handing it the library and role that bound it.</p>
     */
    public ChatContext {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** The same caller, before any conversation was carried. */
    public ChatContext(Long libraryId, String libraryName, Long userId, Role role, CatalogueLookup catalogue) {
        this(libraryId, libraryName, userId, role, catalogue, List.of());
    }

    /** A context with no catalogue behind it - the shape every non-catalogue question has. */
    public ChatContext(Long libraryId, String libraryName, Long userId, Role role) {
        this(libraryId, libraryName, userId, role, null);
    }

    /** The same caller, now with what their library holds. */
    /**
     * The context for somebody who has not signed in.
     *
     * <p>Every field is null on purpose. There is no library to scope to, no
     * account to attribute, and no role to widen anything - so a provider
     * handed this cannot mention a member, a loan or a library id, because it
     * was never given one. The absence is the safety property, not a check
     * somewhere downstream.</p>
     */
    public static ChatContext anonymous() {
        return new ChatContext(null, null, null, null, null);
    }

    /** Whether nobody is signed in. */
    public boolean isAnonymous() {
        return userId == null;
    }

    public ChatContext withCatalogue(CatalogueLookup lookup) {
        return new ChatContext(libraryId, libraryName, userId, role, lookup, history);
    }

    /**
     * The same caller, now with the conversation so far.
     *
     * <p>Separate from the constructors so the history is added where it is
     * trimmed - by {@code ChatService}, after the boundary has bounded it and
     * before any provider sees it.</p>
     */
    public ChatContext withHistory(List<ChatTurn> conversation) {
        return new ChatContext(libraryId, libraryName, userId, role, catalogue, conversation);
    }

    /** Whether anything was said before this question. */
    public boolean hasHistory() {
        return !history.isEmpty();
    }

    /** Whether a catalogue question was recognised and looked up. */
    public boolean hasCatalogue() {
        return catalogue != null;
    }

    /** Whether the caller is staff, and so may be told about a library's own workings. */
    public boolean isStaff() {
        return role == Role.ROLE_ADMIN || role == Role.ROLE_LIBRARIAN;
    }
}
