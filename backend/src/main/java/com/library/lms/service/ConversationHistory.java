package com.library.lms.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.library.lms.dto.ChatRole;
import com.library.lms.dto.ChatTurn;

/**
 * What of an earlier conversation is worth carrying, and what is safe to.
 *
 * <p>Pure and static, so every rule here can be read and tested without a
 * database, a provider or a request. There is no state: a conversation lives in
 * the client that is having it, and this class only decides how much of what it
 * sends back is used.
 *
 * <p><b>Three jobs, and the order matters.</b> Trim to a bounded window, scrub
 * what could be read as an instruction, and work out whether the new question is
 * a follow-up that needs its subject carried forward. Trimming first means the
 * scrubbing and the searching are both over a small, known amount of text rather
 * than over whatever a caller chose to send.
 *
 * <p><b>None of it is trusted.</b> Nothing here grants anything. The caller's
 * library and role come from their token on every request and are never read out
 * of history, so a forged turn claiming to be staff, or claiming the assistant
 * agreed to something, changes nothing about what is looked up or returned.
 */
final class ConversationHistory {

    /**
     * The most turns carried into a prompt.
     *
     * <p>Ten is five exchanges, which is where a library conversation has said
     * what it needs to. The limit exists because a prompt grows with it: a
     * caller who could send five hundred turns could make one question cost as
     * much as a hundred.</p>
     */
    static final int MAX_TURNS = 10;

    /**
     * The most characters carried, across all turns.
     *
     * <p>A second bound, because ten turns of a thousand characters each is
     * still ten thousand characters. Whichever limit bites first wins, and the
     * oldest turns are dropped to meet it.</p>
     */
    static final int MAX_CHARS = 4000;

    /**
     * Words that make a question refer to something already said.
     *
     * <p>"Do you have it?" is only answerable with what came before. When one of
     * these appears and the question names nothing itself, the subject of the
     * previous question is carried forward - which is the whole of what makes a
     * follow-up work.</p>
     */
    private static final List<String> REFERRING_WORDS =
            List.of(" it ", " that ", " this ", " them ", " those ", " these ", " one ", " its ",
                    " the book ", " the same ");

    /**
     * Ways of asking about something without naming it, which refer back when
     * nothing follows them.
     *
     * <p>Two kinds, and they work the same way. A format: "do you have a digital
     * copy?" contains no pronoun and names no book, and can only mean the one
     * just discussed. An attribute: "who is the author?" is the same question
     * about a different field. Without these, both were answered by searching
     * the catalogue for the words themselves - "nothing matching 'digital
     * copy'", "nothing matching 'who is the'".
     *
     * <p><b>Matched at the end of the question, not anywhere in it.</b> "Is
     * there a digital copy of Dune?" and "who is the author of Dune?" name their
     * own subject, and answering either about whatever book came before would be
     * worse than not carrying anything. Ending with the phrase is what
     * distinguishes the two cases, so these are checked with {@code endsWith}
     * while {@link #REFERRING_WORDS} is checked anywhere - a pronoun refers back
     * wherever it sits.</p>
     */
    private static final List<String> TRAILING_REFERENCES =
            List.of(" a digital copy ", " a digital version ", " a digital one ", " an ebook ",
                    " an e book ", " a pdf ", " an epub ", " an audiobook ", " an audio book ",
                    " the author ", " the writer ", " the publisher ", " the category ",
                    " the isbn ", " the genre ", " it about ", " that about ");

    /**
     * Words that make a line a pleasantry rather than a question.
     *
     * <p>"thanks, that helps" refers back - but it is gratitude, not a question
     * needing the catalogue. Without this it would be treated as a follow-up and
     * answered with book facts, which is a worse reply than a plain "you are
     * welcome".</p>
     */
    private static final List<String> CONVERSATIONAL_WORDS =
            List.of(" hi ", " hello ", " hey ", " thanks ", " thank ", " cheers ", " bye ", " goodbye ",
                    " morning ", " afternoon ", " evening ", " ok ", " okay ", " great ", " perfect ",
                    " sorry ", " welcome ");

    /**
     * Words that open a question.
     *
     * <p>Checked at the <b>start</b> of the line rather than anywhere in it,
     * because that is where English puts them: "is it available?" asks something,
     * while "ok that is fine" merely contains the same word. Matching anywhere
     * would make every sentence with "is" in it a question.</p>
     *
     * <p>Checked so that "thanks, do you have it?" stays a follow-up: gratitude
     * and a question in one line is still a question.</p>
     */
    private static final List<String> INTERROGATIVE_WORDS =
            List.of(" who ", " what ", " where ", " when ", " why ", " how ", " do ", " does ", " did ",
                    " is ", " are ", " was ", " can ", " could ", " will ", " would ", " any ", " have ",
                    " show ", " tell ", " find ");

    /** Longer than this and a question is stating its own subject, not referring back. */
    private static final int FOLLOW_UP_MAX_LENGTH = 120;

    private ConversationHistory() {
    }

    /**
     * The turns worth sending, newest kept, oldest dropped.
     *
     * <p>Blank turns and turns with no role are dropped outright rather than
     * repaired: a client that sends them is confused, and guessing what it meant
     * would put invented words in a prompt.</p>
     *
     * @param history whatever the client sent, possibly null
     * @return a bounded, scrubbed, oldest-first list - never null
     */
    static List<ChatTurn> trim(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        // Walked backwards so the newest turns are the ones kept when either
        // limit bites: the end of a conversation is what the next question is
        // about.
        Deque<ChatTurn> kept = new ArrayDeque<>();
        int characters = 0;

        for (int index = history.size() - 1; index >= 0; index--) {
            ChatTurn turn = history.get(index);

            if (turn == null || turn.role() == null || turn.message() == null || turn.message().isBlank()) {
                continue;
            }

            String scrubbed = scrub(turn.message());
            if (scrubbed.isBlank()) {
                continue;
            }

            if (kept.size() >= MAX_TURNS || characters + scrubbed.length() > MAX_CHARS) {
                break;
            }

            kept.addFirst(new ChatTurn(turn.role(), scrubbed));
            characters += scrubbed.length();
        }

        return List.copyOf(kept);
    }

    /**
     * One message, with what could be mistaken for structure taken out.
     *
     * <p><b>This is not the defence against prompt injection.</b> The defences
     * are that the system prompt is the server's and is rebuilt every request,
     * that history is passed as conversation turns rather than as instructions,
     * and that authorization is never read from any of it. This only removes the
     * cheap tricks: control characters that could break a transport, and the
     * turn markers a model might read as a new speaker.
     *
     * <p>Content is left otherwise intact. Rewriting somebody's words to look
     * safe produces a prompt that misquotes them, which is its own problem.
     */
    private static String scrub(String message) {
        String withoutControls = message.codePoints()
                .filter(codePoint -> codePoint == '\n' || codePoint == '\t' || !Character.isISOControl(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();

        // The sequences a chat transport uses to mark who is speaking. Left in,
        // a turn could appear to open a new one.
        return withoutControls
                .replace("\n\nHuman:", "\n\n(quoted) Human:")
                .replace("\n\nAssistant:", "\n\n(quoted) Assistant:")
                .replace("<|", "<(")
                .replace("|>", ")>")
                .trim();
    }

    /**
     * The earlier question this one is really about, when it is about one.
     *
     * <p>Returned only when the new question refers back without naming
     * anything - "do you have it?" after "who wrote Clean Code?". The caller
     * uses it to look the catalogue up again for the same subject, which is what
     * makes a follow-up answerable.
     *
     * <p>Empty when the question stands on its own, so a greeting or a fresh
     * question never drags the last book into the prompt. That matters: a
     * conversation that mentioned a book and then said "thanks" should not be
     * answered with catalogue facts.
     *
     * @param message the new question
     * @param history the trimmed history
     * @return the most recent user turn to reuse as the subject, or empty
     */
    static Optional<String> carriedSubject(String message, List<ChatTurn> history) {
        if (message == null || history == null || history.isEmpty() || !isFollowUp(message)) {
            return Optional.empty();
        }

        // The most recent thing the person themselves asked. The assistant's own
        // turns are not used as a subject: they are this system's words, and
        // searching the catalogue for them would be searching for its own
        // output.
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatTurn turn = history.get(index);

            if (turn.role() == ChatRole.USER && !isFollowUp(turn.message())) {
                return Optional.of(turn.message());
            }
        }

        return Optional.empty();
    }

    /**
     * Whether a question leans on something already said.
     *
     * <p>Short, and containing a word that points backwards. Both conditions,
     * because "Is it true that Clean Code has a second edition?" names its own
     * subject and needs nothing carried.</p>
     */
    /**
     * Whether a line is asking something, rather than only being polite.
     *
     * <p>A question mark settles it. Otherwise the first word decides, with any
     * leading pleasantries skipped - so "thanks, do you have it" asks and "ok
     * that is fine" does not, although both contain words from the same list.</p>
     */
    private static boolean isAsking(String padded, String original) {
        if (original.contains("?")) {
            return true;
        }

        // Walk past the opening pleasantries to whatever the line actually
        // starts with.
        for (String word : padded.trim().split(" ")) {
            String padded1 = " " + word + " ";

            if (CONVERSATIONAL_WORDS.contains(padded1)) {
                continue;
            }
            return INTERROGATIVE_WORDS.contains(padded1);
        }

        return false;
    }

    private static boolean isFollowUp(String message) {
        if (message == null || message.length() > FOLLOW_UP_MAX_LENGTH) {
            return false;
        }

        // Punctuation becomes space and the whole is padded, so a word is matched
        // as a word wherever it sits: "it" must not match "edition", and "the
        // book?" must match as surely as "the book is".
        String padded = " " + message.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";

        // A pleasantry is not a follow-up, even when it points backwards.
        // "thanks, that helps" should be answered as thanks; only a line that is
        // also asking something keeps its claim on the earlier subject.
        boolean pleasantry = CONVERSATIONAL_WORDS.stream().anyMatch(padded::contains);

        if (pleasantry && !isAsking(padded, message)) {
            return false;
        }

        return REFERRING_WORDS.stream().anyMatch(padded::contains)
                || TRAILING_REFERENCES.stream().anyMatch(padded::endsWith);
    }
}
