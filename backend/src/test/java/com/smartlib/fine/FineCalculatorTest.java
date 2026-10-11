package com.smartlib.fine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FineCalculatorTest {

    @ParameterizedTest(name = "due {0}, returned {1}, grace {2}, rate {3}, cap {4} -> {5}")
    @CsvSource({
            "2026-10-10, 2026-10-10, 0, 500, 50000, 0",
            "2026-10-10, 2026-10-09, 0, 500, 50000, 0",
            "2026-10-10, 2026-10-11, 0, 500, 50000, 500",
            "2026-10-10, 2026-10-16, 0, 500, 50000, 3000",
            "2026-10-10, 2026-10-16, 2, 500, 50000, 2000",
            "2026-10-10, 2026-10-11, 1, 500, 50000, 0",
            "2026-10-10, 2027-02-10, 0, 500, 20000, 20000",
            "2026-10-10, 2026-10-20, 0, 500, 0, 5000",
            "2026-12-30, 2027-01-02, 0, 300, 50000, 900"})
    void calculatesOverdueFines(LocalDate due, LocalDate returned, int grace, long rate, long cap, long expected) {
        assertThat(FineCalculator.amount(due, returned, grace, rate, cap)).isEqualTo(expected);
    }
}
