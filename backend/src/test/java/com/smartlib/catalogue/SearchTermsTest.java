package com.smartlib.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchTermsTest {

    @Test
    void buildsRequiredPrefixTermsAndDropsOperatorsAndStopwords() {
        assertThat(SearchTerms.booleanQuery("Clean Code")).isEqualTo("+clean* +code*");
        assertThat(SearchTerms.booleanQuery("The Art of War")).isEqualTo("+art* +war*");
        assertThat(SearchTerms.booleanQuery("+java -\"python\" (x)~*")).isEqualTo("+java* +python*");
        assertThat(SearchTerms.booleanQuery("a b")).isNull();
        assertThat(SearchTerms.looseQuery("java programming")).isEqualTo("java* programming*");
    }

    @Test
    void stripsConversationalFiller() {
        assertThat(SearchTerms.stripFiller("good java books available")).isEqualTo("java");
        assertThat(SearchTerms.stripFiller("books")).isEqualTo("books");
    }
}
