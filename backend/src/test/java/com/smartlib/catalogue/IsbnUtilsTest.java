package com.smartlib.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IsbnUtilsTest {

    @Test
    void normalizesHyphensSpacesAndPrefix() {
        assertThat(IsbnUtils.parse("978-0-13-235088-4")).contains(new IsbnUtils.Isbn("9780132350884", "0132350882"));
        assertThat(IsbnUtils.parse(" ISBN: 978 0132 350884 ")).isPresent();
        assertThat(IsbnUtils.parse("0-13-235088-2")).contains(new IsbnUtils.Isbn("9780132350884", "0132350882"));
    }

    @Test
    void handlesIsbn10WithXCheckDigit() {
        assertThat(IsbnUtils.isValid10("080442957X")).isTrue();
        assertThat(IsbnUtils.parse("0-8044-2957-x")).map(IsbnUtils.Isbn::isbn13).contains("9780804429573");
        assertThat(IsbnUtils.to10("9780804429573")).isEqualTo("080442957X");
    }

    @Test
    void rejectsBadChecksumsAndShapes() {
        assertThat(IsbnUtils.parse("9780132350885")).isEmpty();
        assertThat(IsbnUtils.parse("0132350883")).isEmpty();
        assertThat(IsbnUtils.parse("12345")).isEmpty();
        assertThat(IsbnUtils.parse("X132350882")).isEmpty();
    }

    @Test
    void isbn979HasNoIsbn10() {
        String isbn = com.smartlib.support.CatalogueFixture.isbn13(42);
        assertThat(IsbnUtils.parse(isbn)).contains(new IsbnUtils.Isbn(isbn, null));
    }

    @Test
    void looksLikeIsbnOnlyForIsbnShapedInput() {
        assertThat(IsbnUtils.looksLikeIsbn("978-0-13-235088-4")).isTrue();
        assertThat(IsbnUtils.looksLikeIsbn("java")).isFalse();
        assertThat(IsbnUtils.looksLikeIsbn("clean code 2008")).isFalse();
    }
}
