package com.expensetracker.upi;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remembers, per phone number, the UPI ID the user last used for that contact - picking the
 * same contact again in the manual-entry dialog then prefills their UPI ID directly instead
 * of needing to re-guess a bank handle. Keyed by a normalized 10-digit number so "+91 98765
 * 43210", "098765 43210" and "9876543210" all resolve to the same saved entry.
 *
 * This does NOT look up a UPI ID from a phone number automatically - no such lookup is
 * available to a third-party app (that mapping lives inside NPCI's UPI switch, accessible
 * only to licensed banks/PSPs). It only remembers what the user has already told this app
 * (by scanning a QR, typing a UPI ID, or confirming a guessed one) so they don't retype it.
 */
final class ContactStore {
    private static final String PREFS = "contacts";
    private static final String DELIM = "\u0001";

    private ContactStore() {}

    static String normalize(String rawPhone) {
        String digits = rawPhone == null ? "" : rawPhone.replaceAll("[^0-9]", "");
        if (digits.length() > 10) digits = digits.substring(digits.length() - 10); // strip a leading country code (e.g. 91)
        return digits;
    }

    /** @return the remembered UPI ID for this phone number, or null if none saved yet. */
    static String upiIdFor(Context ctx, String phone) {
        String key = normalize(phone);
        if (key.length() != 10) return null;
        String raw = prefs(ctx).getString(key, null);
        if (raw == null) return null;
        String[] parts = raw.split(DELIM, -1);
        return parts.length >= 2 && !parts[1].isEmpty() ? parts[1] : null;
    }

    /** @return the remembered display name for this phone number, or null. */
    static String nameFor(Context ctx, String phone) {
        String key = normalize(phone);
        if (key.length() != 10) return null;
        String raw = prefs(ctx).getString(key, null);
        if (raw == null) return null;
        String[] parts = raw.split(DELIM, -1);
        return parts.length >= 1 && !parts[0].isEmpty() ? parts[0] : null;
    }

    /** Overwrites any previously remembered UPI ID for this phone number - so if a guess was
     * wrong and the user corrects it, the correction is what's remembered next time. */
    static void remember(Context ctx, String phone, String name, String upiId) {
        String key = normalize(phone);
        if (key.length() != 10 || upiId == null || upiId.isEmpty()) return;
        prefs(ctx).edit().putString(key, (name == null ? "" : name) + DELIM + upiId).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
