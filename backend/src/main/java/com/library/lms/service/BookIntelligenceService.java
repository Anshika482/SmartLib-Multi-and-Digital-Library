package com.library.lms.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.Book;
import com.library.lms.entity.Category;
import com.library.lms.entity.DigitalResource;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.BookSpecifications;
import com.library.lms.repository.DigitalResourceRepository;

/**
 * Works out whether a question is about the catalogue, and if so answers it
 * from the caller's own library.
 *
 * <p><b>The assistant never reaches the database; this class does.</b> It runs
 * the same library-scoped queries the catalogue endpoints run, turns what comes
 * back into {@link BookFact}s, and hands over that list. An assistant is given
 * the list and no repository, so there is no query it can widen, no library it
 * can reach, and no field it can read that a {@code BookFact} does not carry -
 * which is what keeps a model's suggestibility away from the data.</p>
 *
 * <p><b>The library is a parameter, not a guess.</b> It comes from the caller's
 * authenticated account by way of {@link ChatContext}, and every query below
 * names it, so a question mentioning another library still searches the
 * caller's own.</p>
 *
 * <p><b>Nothing about a person is read.</b> The queries touch books and their
 * categories. Loans, members, fines and accounts are not joined, not selected
 * and not reachable from here, so no answer can carry them however a question
 * is phrased.</p>
 *
 * <p><b>Resources follow the books, and follow the same rules.</b> Whatever a
 * matching book has to read online is looked up through the digital resource
 * repository's own library-scoped finders - the enabled-only one for a member,
 * the full one for staff, which is exactly what the resource API decides for
 * each. A member therefore learns nothing here that they could not already see
 * at {@code GET /api/digital-resources}.</p>
 *
 * <p><b>Read-only and bounded.</b> At most {@value #MAX_BOOKS} books and
 * {@value #MAX_RESOURCES} resources, so a question cannot pull a whole
 * catalogue into an answer - or into a provider's request.</p>
 */
@Component
public class BookIntelligenceService {

    /** The most books one answer may be built from. */
    static final int MAX_BOOKS = 5;

    /** The most digital resources one answer may be built from, across every matching book. */
    static final int MAX_RESOURCES = 5;

    /** Phrases that mean "what has this library got", with the words that introduce the thing sought. */
    private static final List<Trigger> TRIGGERS = List.of(
            new Trigger(CatalogueIntent.CATEGORY, List.of("category", "genre", "section")),
            new Trigger(CatalogueIntent.AUTHOR, List.of("written by", "books by", "author", " by ")),
            new Trigger(CatalogueIntent.AVAILABILITY,
                    List.of("available", "in stock", "can i borrow", "copies of", "on the shelf")),
            new Trigger(CatalogueIntent.DETAILS, List.of("tell me about", "details of", "details about", "about")),
            new Trigger(CatalogueIntent.TITLE,
                    // "wrote" asks for a title's author, so the title is what is
                    // looked up - the book's own record carries the author. It
                    // sits here rather than under AUTHOR because "who wrote
                    // Dune?" names a book, not a person; "written by" and "books
                    // by", which do name a person, are matched above this.
                    List.of("do you have", "looking for", "search for", "find", "book called",
                            "books called", "titled", "wrote")));

    /**
     * Ways of asking for something to read.
     *
     * <p>Checked before the triggers below, because a recommendation can be
     * worded with the same words as a search - "suggest something about Java"
     * contains "about", which would otherwise be read as a request for details
     * of a book called "Java".
     *
     * <p>Unlike every trigger, a match here does not need a term: "what should I
     * read next?" names nothing, and the honest answer is a few of the books the
     * library actually holds.</p>
     */
    private static final List<String> RECOMMENDATION_PHRASES = List.of(
            "recommend", "recommendation", "suggest", "suggestion",
            "what should i read", "what can i read", "what to read", "something to read",
            "anything to read", "anything good", "any good books", "worth reading",
            "i want to learn", "want to learn", "help me learn", "books for learning",
            "book ideas", "surprise me", "interesting to read");

    /** Words that carry no subject once a recommendation has been recognised. */
    private static final List<String> TOPIC_FILLER = List.of(
            "me", "some", "a", "an", "any", "the", "book", "books", "something", "anything",
            "good", "great", "best", "interesting", "nice", "new", "to", "read", "reading",
            "about", "on", "for", "in", "of", "with", "by", "written", "i", "want", "would", "like", "learn",
            "learning", "next", "please", "can", "you", "could", "do", "have", "got", "and",
            "study", "studying", "beginner", "beginners", "starter", "from", "my", "get", "give",
            "show", "tell", "find", "looking", "look", "need", "is", "are", "there", "it", "that",
            "this", "am", "will", "should", "if", "just", "really", "maybe", "please");

    private final BookRepository bookRepository;

    private final DigitalResourceRepository resourceRepository;

    public BookIntelligenceService(BookRepository bookRepository, DigitalResourceRepository resourceRepository) {
        this.bookRepository = bookRepository;
        this.resourceRepository = resourceRepository;
    }

    /**
     * What the caller's library holds, if the question was about the catalogue
     * at all.
     *
     * @param message   the caller's question
     * @param libraryId the caller's own library, from their account
     * @param staff     whether the caller may see resources their library has
     *                  switched off - the same rule the resource API applies,
     *                  and one that comes from their account rather than from
     *                  anything in the question
     * @return the lookup, or empty when the question was not a catalogue one -
     *         in which case no query is run
     */
    @Transactional(readOnly = true)
    public Optional<CatalogueLookup> lookup(String message, Long libraryId, boolean staff) {
        if (message == null || message.isBlank() || libraryId == null) {
            return Optional.empty();
        }

        String asked = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();

        if (isRecommendation(asked)) {
            String topic = topicIn(asked);
            List<Book> books = books(CatalogueIntent.RECOMMENDATION, topic, libraryId);

            return Optional.of(new CatalogueLookup(CatalogueIntent.RECOMMENDATION, topic,
                    books.stream().map(BookIntelligenceService::toFact).toList(),
                    resourcesOf(books, libraryId, staff)));
        }

        for (Trigger trigger : TRIGGERS) {
            Optional<String> term = trigger.termIn(asked);
            if (term.isPresent()) {
                List<Book> books = books(trigger.intent(), term.get(), libraryId);

                return Optional.of(new CatalogueLookup(trigger.intent(), term.get(),
                        books.stream().map(BookIntelligenceService::toFact).toList(),
                        resourcesOf(books, libraryId, staff)));
            }
        }

        return Optional.empty();
    }

    /** Whether the question is asking for something to read rather than for a book it names. */
    static boolean isRecommendation(String asked) {
        String padded = " " + asked.replaceAll("[^a-z0-9]+", " ").trim() + " ";

        return RECOMMENDATION_PHRASES.stream().anyMatch(phrase -> padded.contains(" " + phrase + " ")
                || padded.contains(" " + phrase));
    }

    /**
     * The subject of a recommendation, or empty for "suggest me anything".
     *
     * <p>Built by removing the recommendation wording and then the words that
     * carry no subject, which is the same idea as {@code stripLeadingWords} and
     * for the same reason: "suggest me a good book" must not become a search for
     * "good book". Whatever survives is a topic somebody typed - "java", "dbms",
     * "frank herbert" - and is matched against title, author and category.</p>
     */
    static String topicIn(String asked) {
        String text = asked.replaceAll("[^a-z0-9]+", " ").trim();

        // Each phrase takes whatever letters follow it, so an inflected form goes
        // whole: "recommendation" and "recommendations" both disappear rather
        // than leaving "ation" or "s" behind to be searched for as a subject.
        // Longest first as well, so a phrase that contains a shorter one is
        // matched as itself. Every phrase is plain letters and spaces, so none of
        // this needs escaping.
        for (String phrase : RECOMMENDATION_PHRASES.stream()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            text = text.replaceAll(phrase + "[a-z]*", " ");
        }

        StringBuilder topic = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isBlank() || TOPIC_FILLER.contains(word)) {
                continue;
            }
            topic.append(topic.isEmpty() ? "" : " ").append(word);
        }

        String term = topic.toString().trim();

        // The same ceiling clean() uses: a term long enough to be a sentence is
        // not a subject somebody typed.
        return term.length() > 100 ? term.substring(0, 100).trim() : term;
    }

    /**
     * The same look-up for somebody who has not signed in.
     *
     * <p>Two differences from {@link #lookup}, and both are the point:</p>
     * <ul>
     *   <li><b>It is not scoped to a library</b>, because a visitor has none.
     *       It searches the catalogue the public API already exposes.</li>
     *   <li><b>It returns bibliographic facts and no resources.</b> Copy counts
     *       and digital resources are not public, so they are not gathered -
     *       not gathered and then filtered, but never read at all.</li>
     * </ul>
     *
     * <p>The intent detection is shared with the signed-in path, so a visitor's
     * question is understood the same way; only what may answer it differs.</p>
     */
    public Optional<CatalogueLookup> publicLookup(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        String asked = message.toLowerCase(Locale.ROOT).replaceAll("\s+", " ").trim();

        if (isRecommendation(asked)) {
            String topic = topicIn(asked);

            // Bibliographic facts and no resources, exactly as every other public
            // answer: a visitor is told what exists, never how many copies are on
            // the shelf or what can be opened online.
            return Optional.of(new CatalogueLookup(CatalogueIntent.RECOMMENDATION, topic,
                    publicBooks(CatalogueIntent.RECOMMENDATION, topic).stream()
                            .map(BookIntelligenceService::toBibliographicFact).toList(),
                    List.of()));
        }

        for (Trigger trigger : TRIGGERS) {
            Optional<String> term = trigger.termIn(asked);
            if (term.isPresent()) {
                List<Book> books = publicBooks(trigger.intent(), term.get());

                return Optional.of(new CatalogueLookup(trigger.intent(), term.get(),
                        books.stream().map(BookIntelligenceService::toBibliographicFact).toList(),
                        List.of()));
            }
        }

        return Optional.empty();
    }

    /** Matching books from every library, since a visitor belongs to none. */
    private List<Book> publicBooks(CatalogueIntent intent, String term) {
        if (intent == CatalogueIntent.RECOMMENDATION) {
            return bookRepository.findAll(
                    term.isBlank() ? BookSpecifications.always() : BookSpecifications.matchesTopic(term),
                    PageRequest.of(0, MAX_BOOKS, Sort.by("title"))).getContent();
        }

        Specification<Book> specification = intent == CatalogueIntent.CATEGORY
                ? BookSpecifications.hasCategoryNamed(term)
                : BookSpecifications.matchesKeyword(term);

        return bookRepository.findAll(specification, PageRequest.of(0, MAX_BOOKS)).getContent();
    }

    /**
     * A book as a visitor may hear about it: title, author, category, ISBN.
     *
     * <p>No copy counts. {@link BookFact#bibliographic} leaves them null, and
     * the description a provider is given omits the clause rather than
     * printing a zero.</p>
     */
    private static BookFact toBibliographicFact(Book book) {
        return BookFact.bibliographic(
                book.getTitle(),
                book.getAuthor(),
                book.getCategory() == null ? null : book.getCategory().getName(),
                book.getIsbn());
    }

    /** The caller's own library's books matching the term. */
    private List<Book> books(CatalogueIntent intent, String term, Long libraryId) {
        if (intent == CatalogueIntent.RECOMMENDATION) {
            // Library first and always, exactly as every other read here: a
            // recommendation is drawn from the caller's own library and there is
            // no branch that could reach another one. A blank topic narrows
            // nothing, so it is the library scope alone - bounded to MAX_BOOKS,
            // and ordered so the same question twice gives the same answer.
            Specification<Book> scoped = BookSpecifications.belongsToLibrary(libraryId);

            return bookRepository.findAll(
                    term.isBlank() ? scoped : scoped.and(BookSpecifications.matchesTopic(term)),
                    PageRequest.of(0, MAX_BOOKS, Sort.by("title"))).getContent();
        }

        return intent == CatalogueIntent.CATEGORY
                ? bookRepository.findByLibraryIdAndCategoryName(libraryId, term, PageRequest.of(0, MAX_BOOKS))
                        .getContent()
                : bookRepository.findAll(
                        BookSpecifications.belongsToLibrary(libraryId).and(BookSpecifications.matchesKeyword(term)),
                        PageRequest.of(0, MAX_BOOKS)).getContent();
    }

    /**
     * What those books have to read online, within the same library.
     *
     * <p>Two finders, chosen by who is asking: a member gets the enabled-only
     * one, so a resource their library has switched off is as absent from an
     * answer as it is from their own list at
     * {@code GET /api/digital-resources}; staff get the view they have there
     * too. Both name the library as well as the book, so neither can reach
     * across - and the running total is what bounds the whole answer, not each
     * book separately.</p>
     */
    private List<ResourceFact> resourcesOf(List<Book> books, Long libraryId, boolean staff) {
        List<ResourceFact> facts = new ArrayList<>();

        for (Book book : books) {
            if (facts.size() >= MAX_RESOURCES) {
                break;
            }

            Pageable page = PageRequest.of(0, MAX_RESOURCES - facts.size());
            List<DigitalResource> resources = staff
                    ? resourceRepository.findByLibraryIdAndBookId(libraryId, book.getId(), page).getContent()
                    : resourceRepository.findByLibraryIdAndBookIdAndEnabledTrue(libraryId, book.getId(), page)
                            .getContent();

            for (DigitalResource resource : resources) {
                // Checked here as well as in the page size: the bound on what
                // reaches a prompt is this application's to keep, not something
                // to delegate to a query honouring the size it was asked for.
                if (facts.size() >= MAX_RESOURCES) {
                    break;
                }
                facts.add(toFact(resource, book));
            }
        }

        return List.copyOf(facts);
    }

    /** A resource, reduced to what an assistant may be told. No link, no id, no state. */
    private static ResourceFact toFact(DigitalResource resource, Book book) {
        return new ResourceFact(
                book.getTitle(),
                resource.getTitle(),
                resource.getDescription(),
                resource.getResourceType());
    }

    /** A book, reduced to what an assistant may be told. Nothing here comes from a person's record. */
    private static BookFact toFact(Book book) {
        Category category = book.getCategory();

        return new BookFact(
                book.getTitle(),
                book.getAuthor(),
                category == null ? null : category.getName(),
                book.getIsbn(),
                book.getAvailableCopies() == null ? 0 : book.getAvailableCopies(),
                book.getTotalCopies() == null ? 0 : book.getTotalCopies());
    }

    /**
     * One kind of catalogue question, and the phrases that introduce what is
     * being asked about.
     */
    private record Trigger(CatalogueIntent intent, List<String> phrases) {

        /**
         * What is being asked about, on whichever side of the phrase it sits.
         *
         * <p>English puts it on both. "copies of Dune" names the book after the
         * phrase; "is Dune available" names it before - and that second shape
         * is how most people ask whether they can borrow something, so taking
         * only what follows would miss the commonest availability question
         * there is.</p>
         *
         * <p>A phrase with nothing usable on either side is not a search: "is
         * it available?" names no book, so it produces no term and no
         * query.</p>
         */
        Optional<String> termIn(String asked) {
            for (String phrase : phrases) {
                int at = asked.indexOf(phrase);
                if (at < 0) {
                    continue;
                }

                String after = clean(asked.substring(at + phrase.length()));

                // Kept as asked, but only if it names something. "do you have
                // any books?" leaves "any books", which is every book rather
                // than a title - searching for it tells somebody their library
                // holds nothing matching "books", which is both wrong and
                // discouraging. Tested by stripping the generic words and
                // seeing whether anything is left; the term itself is passed on
                // unchanged, so a real title is unaffected.
                if (!after.isEmpty() && !stripLeadingWords(after).isEmpty()) {
                    return Optional.of(after);
                }

                String before = clean(stripLeadingWords(asked.substring(0, at)));
                if (!before.isEmpty()) {
                    return Optional.of(before);
                }
            }

            return Optional.empty();
        }

        /**
         * The words a question opens with, removed until something that could
         * be a title is left.
         *
         * <p>"is dune" becomes "dune"; "is it" becomes nothing, which is the
         * right answer for a question that names no book.</p>
         */
        private static String stripLeadingWords(String before) {
            String term = before.replaceAll("[?!.,;:]+", " ").trim();

            // The format words are here for the same reason as "book": on their
            // own they name no title. "do you have a digital copy?" asks about
            // whichever book was being discussed, and searching for the phrase
            // itself answers that the library holds nothing called "digital
            // copy" - which is true and useless. Removing the term instead lets
            // the question fall through to the conversation, where the subject
            // is. A title that merely starts with one of these - "Digital
            // Minimalism" - is unaffected: the stripped form is only used to
            // ask whether anything is left, never as the term to search.
            List<String> openers = List.of("what", "which", "who", "whose", "whom", "is", "are", "was",
                    "were", "do", "does", "did", "you", "have", "has", "got", "can", "i", "we", "tell", "me",
                    "the", "a", "an", "any", "some", "book", "books", "this", "that", "it", "there", "still",
                    "digital", "copy", "copies", "version", "ebook", "e", "pdf", "epub", "online",
                    "anything", "something", "to", "read");

            boolean stripped = true;
            while (stripped && !term.isEmpty()) {
                stripped = false;
                for (String opener : openers) {
                    if (term.equals(opener)) {
                        return "";
                    }
                    if (term.startsWith(opener + " ")) {
                        term = term.substring(opener.length() + 1).trim();
                        stripped = true;
                        break;
                    }
                }
            }

            return term;
        }

        /** The words after the phrase, without the punctuation or filler around them. */
        private static String clean(String rest) {
            String term = rest.replaceAll("[?!.,;:]+", " ").trim();

            for (String filler : List.of("the ", "a ", "an ", "any ", "some ", "books ", "book ")) {
                if (term.startsWith(filler)) {
                    term = term.substring(filler.length()).trim();
                }
            }

            // A term long enough to be a sentence is not a title someone typed;
            // searching for it would match nothing and read like a failure.
            return term.length() > 100 ? term.substring(0, 100).trim() : term;
        }
    }
}
