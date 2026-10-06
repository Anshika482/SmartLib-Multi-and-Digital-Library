package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The cover store, against a real directory.
 *
 * <p>A temporary one per test, so nothing here touches a deployment's files.
 * What is worth proving: an image survives the instance that wrote it, a key
 * cannot name anything outside the store, and tidying up after something
 * already gone is not an error.
 */
class FilesystemCoverImageStorageTest {

    private static final byte[] IMAGE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};

    private static byte[] read(CoverImageStorage.StoredCoverImage stored) throws Exception {
        try (InputStream content = stored.content()) {
            return content.readAllBytes();
        }
    }

    @Test
    void anImageIsStoredAndReadBackByteForByte(@TempDir Path root) throws Exception {
        FilesystemCoverImageStorage storage = new FilesystemCoverImageStorage(root.toString());

        String key = storage.store(7L, 31L, "image/jpeg", IMAGE);
        Optional<CoverImageStorage.StoredCoverImage> stored = storage.read(key);

        assertThat(stored).isPresent();
        assertThat(read(stored.orElseThrow())).isEqualTo(IMAGE);
        assertThat(stored.orElseThrow().contentType()).isEqualTo("image/jpeg");
        assertThat(stored.orElseThrow().sizeInBytes()).isEqualTo(IMAGE.length);
    }

    /**
     * The restart case.
     *
     * <p>A second instance over the same directory - which is what a restart
     * is, as far as the store is concerned - finds what the first one wrote.
     * Nothing about a key depends on the instance that issued it.
     */
    @Test
    void anImageOutlivesTheInstanceThatWroteIt(@TempDir Path root) throws Exception {
        String key = new FilesystemCoverImageStorage(root.toString()).store(7L, 31L, "image/png", IMAGE);

        CoverImageStorage afterRestart = new FilesystemCoverImageStorage(root.toString());

        assertThat(afterRestart.read(key)).isPresent();
        assertThat(read(afterRestart.read(key).orElseThrow())).isEqualTo(IMAGE);
    }

    @Test
    void oneLibrarysFilesSitUnderItsOwnId(@TempDir Path root) {
        FilesystemCoverImageStorage storage = new FilesystemCoverImageStorage(root.toString());

        String seven = storage.store(7L, 31L, "image/jpeg", IMAGE);
        String nine = storage.store(9L, 31L, "image/jpeg", IMAGE);

        assertThat(seven).startsWith("7/31/");
        assertThat(nine).startsWith("9/31/");
        assertThat(Files.isDirectory(root.resolve("7"))).isTrue();
        assertThat(Files.isDirectory(root.resolve("9"))).isTrue();
    }

    @Test
    void twoUploadsForOneBookNeverCollide(@TempDir Path root) {
        FilesystemCoverImageStorage storage = new FilesystemCoverImageStorage(root.toString());

        assertThat(storage.store(7L, 31L, "image/jpeg", IMAGE))
                .isNotEqualTo(storage.store(7L, 31L, "image/jpeg", IMAGE));
    }

    @Test
    void aKeyIsNotGuessableFromTheBookId(@TempDir Path root) {
        String key = new FilesystemCoverImageStorage(root.toString()).store(7L, 31L, "image/jpeg", IMAGE);

        assertThat(key).matches("7/31/[0-9a-f\\-]{36}\\.jpg");
    }

    // ---------- a key from outside is hostile ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "../../../etc/passwd",
            "7/31/../../../../secrets.txt",
            "/etc/passwd",
            "..",
    })
    void aKeyThatEscapesTheStoreNamesNothing(String hostile, @TempDir Path root) {
        FilesystemCoverImageStorage storage = new FilesystemCoverImageStorage(root.toString());

        assertThat(storage.read(hostile))
                .as("a key is only ever issued by store(), but the check does not rely on that")
                .isEmpty();
        assertThatCode(() -> storage.delete(hostile)).doesNotThrowAnyException();
    }

    @Test
    void deletingSomethingAlreadyGoneIsNotAnError(@TempDir Path root) {
        FilesystemCoverImageStorage storage = new FilesystemCoverImageStorage(root.toString());
        String key = storage.store(7L, 31L, "image/jpeg", IMAGE);

        storage.delete(key);

        assertThatCode(() -> storage.delete(key))
                .as("a caller must be able to finish tidying up after a half-completed change")
                .doesNotThrowAnyException();
        assertThat(storage.read(key)).isEmpty();
    }

    @Test
    void readingAKeyThatWasNeverStoredIsEmpty(@TempDir Path root) {
        FilesystemCoverImageStorage storage = new FilesystemCoverImageStorage(root.toString());

        assertThat(storage.read("7/31/never-written.jpg")).isEmpty();
        assertThat(storage.read(null)).isEmpty();
        assertThat(storage.read("  ")).isEmpty();
    }

    @Test
    void nothingPartialIsLeftBehindAfterAStore(@TempDir Path root) throws Exception {
        FilesystemCoverImageStorage storage = new FilesystemCoverImageStorage(root.toString());
        storage.store(7L, 31L, "image/jpeg", IMAGE);

        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).map(path -> path.getFileName().toString()))
                    .as("the write-then-move leaves no .part file")
                    .noneMatch(name -> name.endsWith(".part"));
        }
    }

    @Test
    void eachAcceptedTypeGetsItsOwnExtensionAndIsServedBackAsItself(@TempDir Path root) {
        FilesystemCoverImageStorage storage = new FilesystemCoverImageStorage(root.toString());

        for (String[] each : new String[][]{{"image/jpeg", "jpg"}, {"image/png", "png"}, {"image/webp", "webp"}}) {
            String key = storage.store(7L, 31L, each[0], IMAGE);

            assertThat(key).endsWith("." + each[1]);
            assertThat(storage.read(key).orElseThrow().contentType()).isEqualTo(each[0]);
        }
    }
}
