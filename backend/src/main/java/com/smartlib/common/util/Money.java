package com.smartlib.common.util;

import java.util.Locale;

public final class Money {
    private Money() {
    }

    /** Formats paise as an Indian-rupee string, e.g. 12550 -> "₹125.50". */
    public static String inr(long paise) {
        long rupees = paise / 100;
        long rem = Math.abs(paise % 100);
        return rem == 0 ? String.format(Locale.ROOT, "₹%d", rupees) : String.format(Locale.ROOT, "₹%d.%02d", rupees, rem);
    }
}
