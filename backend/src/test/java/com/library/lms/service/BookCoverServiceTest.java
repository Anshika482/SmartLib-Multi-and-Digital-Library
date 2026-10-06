package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.CoverImageNotFoundException;
import com.library.lms.exception.CoverImageTooLargeException;
import com.library.lms.exception.UnsupportedCoverImageException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.UserRepository;

/**
 * Putting a cover on a book, and everything that must not happen.
 *
 * <p>No filesystem here: the storage is a mock, so what the service asks of it
 * can be read argument by argument - which is where the isolation rule, the
 * type check and the replace-then-delete order actually live.
 */
class BookCoverServiceTest {

    private static final long LIBRARY_ID = 7L;

    private static final long OTHER_LIBRARY_ID = 9L;

    private static final long BOOK_ID = 31L;

    private static final long MAX_BYTES = 2 * 1024 * 1024;

    /** A real JPEG start-of-image marker, then filler. Not a picture, but the right shape. */
    private static final byte[] JPEG = jpeg(64);

    private static final byte[] PNG = concat(
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, new byte[32]);

    private static final byte[] WEBP = concat(
            new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, new byte[32]);

    private final BookRepository bookRepository = mock(BookRepository.class);

    private final UserRepository userRepository = mock(UserRepository.class);

    private final CoverImageStorage storage = mock(CoverImageStorage.class);

    private final BookCoverService service =
            new BookCoverService(bookRepository, userRepository, storage, MAX_BYTES);

    private Library library;
    private Book book;

    private static byte[] jpeg(int totalLength) {
        byte[] bytes = new byte[totalLength];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    @BeforeEach
    void fixtures() {
        library = new Library();
        library.setId(LIBRARY_ID);
        library.setName("Central Library");

        book = new Book();
        book.setId(BOOK_ID);
        book.setTitle("A Book");
        book.setLibrary(library);

        User staff = new User();
        staff.setId(11L);
        staff.setUsername("librarian");
        staff.setRole(Role.ROLE_LIBRARIAN);
        staff.setLibrary(library);
        when(userRepository.findByUsername("librarian")).thenReturn(Optional.of(staff));

        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.of(book));
        when(bookRepository.save(any(Book.class))).thenAnswer(call -> call.getArgument(0));
        when(storage.store(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(LIBRARY_ID + "/" + BOOK_ID + "/new-key.jpg");
    }

    // ---------- upload ----------

    @Test
    void uploadingStoresTheFileAndPointsTheBookAtIt() {
        service.put(BOOK_ID, "image/jpeg", JPEG, "librarian");

        verify(storage).store(LIBRARY_ID, BOOK_ID, "image/jpeg", JPEG);
        assertThat(book.getCoverImageKey()).isEqualTo(LIBRARY_ID + "/" + BOOK_ID + "/new-key.jpg");
        verify(bookRepository).save(book);
    }

    @Test
    void theFileIsStoredUnderTheCallersOwnLibrary() {
        service.put(BOOK_ID, "image/jpeg", JPEG, "librarian");

        ArgumentCaptor<Long> libraryId = ArgumentCaptor.forClass(Long.class);
        verify(storage).store(libraryId.capture(), anyLong(), anyString(), any());

        assertThat(libraryId.getValue())
                .as("taken from the account, never from the request")
                .isEqualTo(LIBRARY_ID);
    }

    @Test
    void everyAcceptedTypeIsAccepted() {
        for (Object[] each : new Object[][]{{"image/jpeg", JPEG}, {"image/png", PNG}, {"image/webp", WEBP}}) {
            Mockito.clearInvocations(storage, bookRepository);

            assertThatCode(() -> service.put(BOOK_ID, (String) each[0], (byte[]) each[1], "librarian"))
                    .as((String) each[0])
                    .doesNotThrowAnyException();
        }
    }

    // ---------- replace ----------

    @Test
    void replacingWritesTheNewFileBeforeRemovingTheOld() {
        book.setCoverImageKey("7/31/old-key.jpg");

        service.put(BOOK_ID, "image/png", PNG, "librarian");

        // Order is the safety property: if the delete ran first, a failure
        // between the two would leave the book pointing at nothing.
        InOrder order = Mockito.inOrder(storage, bookRepository);
        order.verify(storage).store(anyLong(), anyLong(), anyString(), any());
        order.verify(bookRepository).save(book);
        order.verify(storage).delete("7/31/old-key.jpg");
    }

    @Test
    void replacingLeavesNoOrphanWhenTheKeyIsUnchanged() {
        book.setCoverImageKey("7/31/same.jpg");
        when(storage.store(anyLong(), anyLong(), anyString(), any())).thenReturn("7/31/same.jpg");

        service.put(BOOK_ID, "image/jpeg", JPEG, "librarian");

        verify(storage, never()).delete(anyString());
    }

    @Test
    void aFirstUploadDeletesNothing() {
        service.put(BOOK_ID, "image/jpeg", JPEG, "librarian");

        verify(storage, never()).delete(anyString());
    }

    // ---------- delete ----------

    @Test
    void removingClearsTheRowAndTheFile() {
        book.setCoverImageKey("7/31/old-key.jpg");

        service.remove(BOOK_ID, "librarian");

        assertThat(book.getCoverImageKey()).isNull();
        verify(bookRepository).save(book);
        verify(storage).delete("7/31/old-key.jpg");
    }

    @Test
    void removingACoverThatIsNotThereIsNotAnError() {
        assertThatCode(() -> service.remove(BOOK_ID, "librarian")).doesNotThrowAnyException();

        verify(storage, never()).delete(anyString());
        verify(bookRepository, never()).save(any());
    }

    // ---------- invalid type ----------

    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "text/html", "application/pdf", "image/gif", "application/octet-stream"})
    void aTypeThisApplicationDoesNotAcceptIsRefused(String declared) {
        assertThatThrownBy(() -> service.put(BOOK_ID, declared, JPEG, "librarian"))
                .isInstanceOf(UnsupportedCoverImageException.class);

        verify(storage, never()).store(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void bytesThatAreNotTheDeclaredTypeAreRefused() {
        // Declared an image, actually an HTML document. This is the case a
        // content-type check on its own would wave through.
        byte[] html = "<html><script>alert(1)</script></html>".getBytes();

        assertThatThrownBy(() -> service.put(BOOK_ID, "image/png", html, "librarian"))
                .isInstanceOf(UnsupportedCoverImageException.class);

        verify(storage, never()).store(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void theStoredTypeIsWhatTheBytesAreNotWhatTheyClaimed() {
        // Declared JPEG, actually a PNG. Accepted - both are allowed - but
        // stored as what it is, so it is served back correctly.
        service.put(BOOK_ID, "image/jpeg", PNG, "librarian");

        verify(storage).store(anyLong(), anyLong(), eq("image/png"), any());
    }

    @Test
    void anEmptyUploadIsRefused() {
        assertThatThrownBy(() -> service.put(BOOK_ID, "image/jpeg", new byte[0], "librarian"))
                .isInstanceOf(UnsupportedCoverImageException.class);
    }

    // ---------- invalid size ----------

    @Test
    void anUploadOverTheLimitIsRefusedBeforeItIsStored() {
        byte[] huge = jpeg((int) MAX_BYTES + 1);

        assertThatThrownBy(() -> service.put(BOOK_ID, "image/jpeg", huge, "librarian"))
                .isInstanceOf(CoverImageTooLargeException.class)
                .hasMessageContaining("KB");

        verify(storage, never()).store(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void anUploadExactlyAtTheLimitIsAccepted() {
        assertThatCode(() -> service.put(BOOK_ID, "image/jpeg", jpeg((int) MAX_BYTES), "librarian"))
                .doesNotThrowAnyException();
    }

    // ---------- missing cover ----------

    @Test
    void readingACoverFromABookThatHasNoneIs404() {
        assertThatThrownBy(() -> service.read(BOOK_ID, "librarian"))
                .isInstanceOf(CoverImageNotFoundException.class);
    }

    @Test
    void readingACoverWhoseFileHasGoneIsTheSameAnswer() {
        book.setCoverImageKey("7/31/vanished.jpg");
        when(storage.read("7/31/vanished.jpg")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read(BOOK_ID, "librarian"))
                .as("a caller need not tell a missing cover from a missing file")
                .isInstanceOf(CoverImageNotFoundException.class);
    }

    @Test
    void readingACoverReturnsTheStoredImage() {
        book.setCoverImageKey("7/31/there.png");
        when(storage.read("7/31/there.png")).thenReturn(Optional.of(
                new CoverImageStorage.StoredCoverImage(new ByteArrayInputStream(PNG), "image/png", PNG.length)));

        CoverImageStorage.StoredCoverImage cover = service.read(BOOK_ID, "librarian");

        assertThat(cover.contentType()).isEqualTo("image/png");
        assertThat(cover.sizeInBytes()).isEqualTo(PNG.length);
    }

    // ---------- cross-library ----------

    @Test
    void anotherLibrarysBookCannotBeGivenACover() {
        // The book exists, but not in this caller's library, so the scoped
        // finder returns nothing - the same as a book that never existed.
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.put(BOOK_ID, "image/jpeg", JPEG, "librarian"))
                .isInstanceOf(BookNotFoundException.class);

        verify(storage, never()).store(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void anotherLibrarysCoverCannotBeRead() {
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read(BOOK_ID, "librarian"))
                .isInstanceOf(BookNotFoundException.class);

        verify(storage, never()).read(anyString());
    }

    @Test
    void anotherLibrarysCoverCannotBeRemoved() {
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(BOOK_ID, "librarian"))
                .isInstanceOf(BookNotFoundException.class);

        verify(storage, never()).delete(anyString());
    }

    @Test
    void theBookIsAlwaysLookedUpScopedToTheCallersLibrary() {
        service.put(BOOK_ID, "image/jpeg", JPEG, "librarian");

        verify(bookRepository).findByIdAndLibraryId(BOOK_ID, LIBRARY_ID);
        verify(bookRepository, never()).findById(anyLong());
        assertThat(OTHER_LIBRARY_ID).isNotEqualTo(LIBRARY_ID);
    }
}
