package com.smartlib.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    void quotesAndNeutralisesFormulas() {
        String csv = CsvWriter.write(List.of("a", "b"), List.of(List.of("=HYPERLINK(\"x\")", "x,y"), List.of(5, "plain")));
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"").contains("\"x,y\"").contains("5,plain");
    }
}
