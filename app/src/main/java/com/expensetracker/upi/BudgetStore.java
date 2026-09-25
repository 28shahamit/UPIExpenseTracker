package com.expensetracker.upi;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per-category monthly budgets: a target the user sets for any category - one of
 * {@link CategoryMeta#NAMES} or a custom one from {@link CategoryStore} - compared against
 * that category's confirmed spend for the current month in
 * {@link HistoryActivity#bindCategoryBudgets()}. Keying directly off the category's display
 * name means a budget "just works" for any custom category the user has already created or
 * creates later, with no separate registration step.
 *
 * Purely local storage (SharedPreferences): never reads from or writes to the expenses DB,
 * account balances, or anything involved in building/sending a UPI payment request.
 */
final class BudgetStore {
    private static final String PREFS = "category_budgets";

    private BudgetStore() {}

    /** -1 if no budget has been set for this category. */
    static float getBudget(Context ctx, String category) {
        return prefs(ctx).getFloat(key(category), -1f);
    }

    static void setBudget(Context ctx, String category, float amount) {
        prefs(ctx).edit().putFloat(key(category), amount).apply();
    }

    static void clearBudget(Context ctx, String category) {
        prefs(ctx).edit().remove(key(category)).apply();
    }

    private static String key(String category) {
        return "budget_" + (category == null ? "" : category);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
