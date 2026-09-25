package com.expensetracker.upi;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists categories the user has added beyond {@link CategoryMeta}'s fixed defaults, so
 * "add a new category" survives app restarts. Backed by a single SharedPreferences string:
 * custom names joined with a control character that's not realistically typeable in the
 * add-category EditText, kept in the order they were added (a StringSet doesn't preserve
 * insertion order, which is why this isn't just a getStringSet()).
 */
final class CategoryStore {
    private static final String PREFS = "categories";
    private static final String KEY_CUSTOM = "custom_list";
    private static final String DELIM = "\u0001";

    private CategoryStore() {}

    /** User-added categories only, in the order they were added. */
    static List<String> customCategories(Context ctx) {
        String raw = prefs(ctx).getString(KEY_CUSTOM, "");
        List<String> list = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String s : raw.split(DELIM, -1)) {
                if (!s.isEmpty()) list.add(s);
            }
        }
        return list;
    }

    /** {@link CategoryMeta#NAMES} followed by any custom categories, in the order they appear
     * everywhere a category picker is shown (spinner, edit dialog). */
    static List<String> allCategories(Context ctx) {
        List<String> all = new ArrayList<>();
        for (String n : CategoryMeta.NAMES) all.add(n);
        all.addAll(customCategories(ctx));
        return all;
    }

    /**
     * Adds a new category if it isn't already present (case-insensitive) among the defaults
     * or existing custom ones.
     * @return true if added, false if it was blank or already existed.
     */
    static boolean addCategory(Context ctx, String name) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) return false;
        for (String existing : allCategories(ctx)) {
            if (existing.equalsIgnoreCase(name)) return false;
        }
        List<String> custom = customCategories(ctx);
        custom.add(name);
        StringBuilder sb = new StringBuilder();
        for (String c : custom) sb.append(c).append(DELIM);
        prefs(ctx).edit().putString(KEY_CUSTOM, sb.toString()).apply();
        return true;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
