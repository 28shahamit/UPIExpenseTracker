package com.expensetracker.upi;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Display-only rupee formatting (adds Indian-style thousands grouping, e.g. ₹12,34,567.89).
 * Purely cosmetic - nothing here touches the plain "%.2f" amount string that MainActivity
 * sends inside the actual upi://pay request; that formatting is untouched and lives only in
 * MainActivity#launchPayment()/openUpiAppToPayManually().
 */
final class Money {
    private Money() {}

    private static final DecimalFormat FORMAT;
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        FORMAT = new DecimalFormat("#,##,##0.00", symbols);
    }

    /** e.g. 1234567.5 -> "₹12,34,567.50" */
    static String format(double amount) {
        return "\u20B9" + FORMAT.format(amount);
    }

    /** Same as format(), but with a leading +/- sign kept explicit - used where the sign
     * itself is meaningful (e.g. a balance delta), not just magnitude. */
    static String formatSigned(double amount) {
        String sign = amount < 0 ? "-" : "+";
        return sign + "\u20B9" + FORMAT.format(Math.abs(amount));
    }
}
