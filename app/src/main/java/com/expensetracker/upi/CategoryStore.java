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
    private static final String KEY_HIDDEN = "hidden_defaults";
    private static final String DELIM = "\u0001";

    private CategoryStore() {}

    private static boolean isDefault(String name) {
        for (String n : CategoryMeta.NAMES) if (n.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Default names the user has renamed/deleted, so {@link #allCategories} can skip them. */
    private static java.util.Set<String> hiddenDefaults(Context ctx) {
        return prefs(ctx).getStringSet(KEY_HIDDEN, java.util.Collections.emptySet());
    }

    private static void hideDefault(Context ctx, String name) {
        java.util.Set<String> hidden = new java.util.HashSet<>(hiddenDefaults(ctx));
        hidden.add(name.toLowerCase(java.util.Locale.ROOT));
        prefs(ctx).edit().putStringSet(KEY_HIDDEN, hidden).apply();
    }

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

    /** {@link CategoryMeta#NAMES} (minus any the user has renamed/deleted) followed by any
     * custom categories, in the order they appear everywhere a category picker is shown. */
    static List<String> allCategories(Context ctx) {
        java.util.Set<String> hidden = hiddenDefaults(ctx);
        List<String> all = new ArrayList<>();
        for (String n : CategoryMeta.NAMES) {
            if (!hidden.contains(n.toLowerCase(java.util.Locale.ROOT))) all.add(n);
        }
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
        saveCustom(ctx, custom);
        return true;
    }

    /** Removes any category, default or custom. A default is hidden rather than deleted from
     * {@link CategoryMeta} (it's a fixed array); a custom one is dropped from the saved list.
     * Past expenses already saved under this category name keep that name as plain text -
     * this only affects what shows up in future category pickers.
     * @return true if it existed and got removed/hidden. */
    static boolean removeCategory(Context ctx, String name) {
        if (isDefault(name)) {
            hideDefault(ctx, name);
            return true;
        }
        List<String> custom = customCategories(ctx);
        boolean removed = custom.removeIf(c -> c.equalsIgnoreCase(name));
        if (removed) saveCustom(ctx, custom);
        return removed;
    }

    /** Renames any category, default or custom. Renaming a default hides the old fixed name
     * and adds the new one as a custom category - its special emoji/color in {@link
     * CategoryMeta} are keyed to the fixed name, so a renamed default picks up the generic
     * 🏷️ look going forward, same as any other custom category. Past expenses keep their old
     * category text unchanged either way.
     * @return true if renamed, false if newName is blank or collides with an existing category
     * (other than oldName itself). */
    static boolean renameCategory(Context ctx, String oldName, String newName) {
        newName = newName == null ? "" : newName.trim();
        if (newName.isEmpty()) return false;
        for (String existing : allCategories(ctx)) {
            if (existing.equalsIgnoreCase(newName) && !existing.equalsIgnoreCase(oldName)) return false;
        }
        if (isDefault(oldName)) {
            hideDefault(ctx, oldName);
            List<String> custom = customCategories(ctx);
            custom.add(newName);
            saveCustom(ctx, custom);
            return true;
        }
        List<String> custom = customCategories(ctx);
        int idx = -1;
        for (int i = 0; i < custom.size(); i++) if (custom.get(i).equalsIgnoreCase(oldName)) { idx = i; break; }
        if (idx == -1) return false;
        custom.set(idx, newName);
        saveCustom(ctx, custom);
        return true;
    }

    private static void saveCustom(Context ctx, List<String> custom) {
        StringBuilder sb = new StringBuilder();
        for (String c : custom) sb.append(c).append(DELIM);
        prefs(ctx).edit().putString(KEY_CUSTOM, sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
