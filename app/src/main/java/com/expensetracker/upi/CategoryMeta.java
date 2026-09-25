package com.expensetracker.upi;

/**
 * Single source of truth for the expense categories: display name, an emoji used as a
 * lightweight "icon" (no image assets needed), and an accent color for chips/charts.
 * MainActivity's category Spinner and the history/analysis screen both read from here so
 * the two stay in sync and a category's color/emoji is consistent everywhere it appears.
 */
final class CategoryMeta {
    private CategoryMeta() {}

    static final String[] NAMES = {"Food", "Groceries", "Transport", "Bills", "Shopping",
            "Entertainment", "Health", "Education", "Travel", "Other"};

    private static final String[] EMOJI = {"\uD83C\uDF54", "\uD83D\uDED2", "\uD83D\uDE8C", "\uD83D\uDCA1",
            "\uD83D\uDECD\uFE0F", "\uD83C\uDFAC", "\u2695\uFE0F", "\uD83D\uDCDA", "\u2708\uFE0F", "\uD83D\uDCE6"};

    private static final int[] COLOR_RES = {R.color.cat_food, R.color.cat_groceries, R.color.cat_transport,
            R.color.cat_bills, R.color.cat_shopping, R.color.cat_entertainment, R.color.cat_health,
            R.color.cat_education, R.color.cat_travel, R.color.cat_other};

    // Shown for any category not in NAMES (i.e. one the user added via CategoryStore).
    private static final String CUSTOM_EMOJI = "\uD83C\uDFF7\uFE0F"; // 🏷️

    private static int indexOf(String category) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equalsIgnoreCase(category)) return i;
        return -1;
    }

    static String emojiFor(String category) {
        int i = indexOf(category);
        return i >= 0 ? EMOJI[i] : CUSTOM_EMOJI;
    }

    static int colorResFor(String category) {
        int i = indexOf(category);
        if (i >= 0) return COLOR_RES[i];
        // Custom category: no dedicated color, so pick one from the same palette deterministically
        // by name (java.lang.String#hashCode is stable within a run and across runs on the same
        // JVM/Android version) so a given custom category always renders the same color instead
        // of all of them collapsing onto "Other"'s color and becoming visually indistinguishable.
        int pool = Math.floorMod(category == null ? 0 : category.toLowerCase(java.util.Locale.ROOT).hashCode(),
                COLOR_RES.length);
        return COLOR_RES[pool];
    }
}
