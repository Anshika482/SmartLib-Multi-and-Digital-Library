package com.smartlib.fine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Pure overdue-fine rules (unit tested). */
public final class FineCalculator {
    private FineCalculator() {
    }

    public static long overdueDays(LocalDate dueDate, LocalDate returnDate) {
        return Math.max(0, ChronoUnit.DAYS.between(dueDate, returnDate));
    }

    /** Chargeable days = overdue days minus grace; amount = days x rate, capped when a cap (> 0) is configured. */
    public static long amount(LocalDate dueDate, LocalDate returnDate, int graceDays, long perDayPaise, long capPaise) {
        long chargeable = Math.max(0, overdueDays(dueDate, returnDate) - Math.max(0, graceDays));
        long amount = chargeable * Math.max(0, perDayPaise);
        return capPaise > 0 ? Math.min(amount, capPaise) : amount;
    }
}
