package com.expensetracker.upi;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Expense history + spend analysis. Pulls structured {@link Expense} rows via
 * {@link ExpenseDbHelper#getAllExpenses()} and renders them as: a totals card, a per-category
 * breakdown with proportional bars, a search box, a status filter, and a classified
 * RecyclerView list (see {@link ExpenseAdapter}). Tapping a row opens an edit/delete dialog.
 *
 * Does not call, wrap, or alter anything involved in building or sending a UPI payment
 * request; that logic remains entirely in MainActivity#launchPayment(). Editing here only
 * changes the local record (merchant/amount/note/category) - never the status or the
 * txn_ref/response_code/req_tr fields that reflect what actually happened with the payment.
 */
public class HistoryActivity extends AppCompatActivity {

    private ExpenseDbHelper db;
    private List<Expense> allExpenses;
    private ExpenseAdapter adapter;
    private final List<Expense> visible = new ArrayList<>();
    private FilterMode currentMode = FilterMode.ALL;
    private String currentQuery = "";

    // Monthly budget is a pure local-tracking feature - a number the user sets for themselves
    // to compare this month's confirmed spend against. It never reads from, writes to, or
    // otherwise touches anything involved in building/sending a UPI payment request.
    private static final String PREFS_NAME = "expense_tracker_prefs";
    private static final String KEY_MONTHLY_BUDGET = "monthly_budget";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        BottomNav.wire(this, R.id.navDashboard);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        db = new ExpenseDbHelper(this);

        RecyclerView rv = findViewById(R.id.rvExpenses);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ExpenseAdapter(visible, this::showEditDialog);
        rv.setAdapter(adapter);

        bindFilter();
        bindSearch();
        findViewById(R.id.tvEditBudget).setOnClickListener(v -> showSetBudgetDialog());
        findViewById(R.id.llCbHeader).setOnClickListener(v -> {
            View body = findViewById(R.id.llCbBody);
            boolean expand = body.getVisibility() != View.VISIBLE;
            body.setVisibility(expand ? View.VISIBLE : View.GONE);
            ((TextView) findViewById(R.id.tvCbToggle)).setText(expand ? "\u25BE" : "\u25B8");
        });
        reloadAndRefresh();
    }

    private enum FilterMode { ALL, PAID, PENDING, FAILED }

    /** Re-reads every row from the DB and rebinds every section of the screen - called on
     * first load and after any edit/delete so the summary/breakdown/list all stay consistent. */
    private void reloadAndRefresh() {
        allExpenses = db.getAllExpenses();
        bindSummary();
        bindPeriodSummary();
        bindBudget();
        bindCategoryBudgets();
        bindCategoryBreakdown();
        applyFilter();
    }

    /** Today / this-week / this-month confirmed spend, plus this-month vs last-month. All
     * period boundaries use the device's local calendar/timezone, matching how createdAt
     * (System.currentTimeMillis() at insert time) is naturally interpreted by the user. */
    private void bindPeriodSummary() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfToday = cal.getTimeInMillis();

        Calendar weekCal = (Calendar) cal.clone();
        weekCal.set(Calendar.DAY_OF_WEEK, weekCal.getFirstDayOfWeek());
        long startOfWeek = weekCal.getTimeInMillis();

        Calendar monthCal = (Calendar) cal.clone();
        monthCal.set(Calendar.DAY_OF_MONTH, 1);
        long startOfMonth = monthCal.getTimeInMillis();

        Calendar lastMonthStart = (Calendar) monthCal.clone();
        lastMonthStart.add(Calendar.MONTH, -1);
        long startOfLastMonth = lastMonthStart.getTimeInMillis();
        long endOfLastMonth = startOfMonth; // exclusive

        double today = 0, week = 0, month = 0, lastMonth = 0;
        for (Expense e : allExpenses) {
            if (!e.isConfirmed()) continue;
            if (e.createdAt >= startOfToday) today += e.amount;
            if (e.createdAt >= startOfWeek) week += e.amount;
            if (e.createdAt >= startOfMonth) month += e.amount;
            if (e.createdAt >= startOfLastMonth && e.createdAt < endOfLastMonth) lastMonth += e.amount;
        }

        ((TextView) findViewById(R.id.tvSpendToday)).setText("\u20B9" + String.format(Locale.US, "%.2f", today));
        ((TextView) findViewById(R.id.tvSpendWeek)).setText("\u20B9" + String.format(Locale.US, "%.2f", week));
        ((TextView) findViewById(R.id.tvSpendMonth)).setText("\u20B9" + String.format(Locale.US, "%.2f", month));

        TextView tvCompare = findViewById(R.id.tvSpendMonthVsLast);
        if (lastMonth <= 0) {
            tvCompare.setText("This month");
        } else {
            double pct = ((month - lastMonth) / lastMonth) * 100;
            String arrow = pct >= 0 ? "\u25B2" : "\u25BC";
            tvCompare.setText("This month " + arrow + " " + Math.round(Math.abs(pct)) + "% vs last");
        }
    }

    /** Sum of all set per-category budgets (BudgetStore), across every category including
     * custom ones - added to the manual overall budget in bindBudget(), not a replacement
     * for it. */
    private double totalCategoryBudget() {
        double sum = 0;
        for (String category : CategoryStore.allCategories(this)) {
            float b = BudgetStore.getBudget(this, category);
            if (b > 0) sum += b;
        }
        return sum;
    }

    /** Compares this month's confirmed spend against the effective monthly budget: the
     * manual overall figure PLUS the sum of all per-category budgets (BudgetStore) - so
     * setting category budgets adds to, rather than replaces, a manual target. Purely a
     * local comparison; touches nothing else. */
    private void bindBudget() {
        double categoryTotal = totalCategoryBudget();
        float manual = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getFloat(KEY_MONTHLY_BUDGET, -1f);
        double manualPart = manual > 0 ? manual : 0;
        double budget = manualPart + categoryTotal;
        boolean combined = manualPart > 0 && categoryTotal > 0;

        TextView tvSummary = findViewById(R.id.tvBudgetSummary);
        TextView tvEdit = findViewById(R.id.tvEditBudget);
        View track = findViewById(R.id.flBudgetTrack);
        View fill = findViewById(R.id.vBudgetFill);
        tvEdit.setText(budget > 0 ? "Edit" : "Set");

        if (budget <= 0) {
            tvSummary.setText("No budget set for this month yet.");
            track.setVisibility(View.GONE);
            return;
        }

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfMonth = cal.getTimeInMillis();
        double totalSpent = 0, budgetedCategorySpent = 0;
        for (Expense e : allExpenses) {
            if (!e.isConfirmed() || e.createdAt < startOfMonth) continue;
            totalSpent += e.amount;
            if (BudgetStore.getBudget(this, e.category) > 0) budgetedCategorySpent += e.amount;
        }
        // A manual overall figure is meant to cover all spend; a category-budgets-only total
        // should only be compared against spend in those budgeted categories, or an
        // unbudgeted category (like Groceries here) wrongly counts against Food's budget.
        double spent = manualPart > 0 ? totalSpent : budgetedCategorySpent;

        int percent = (int) Math.round(Math.min(1.0, spent / budget) * 100);
        boolean over = spent > budget;
        String amounts = "\u20B9" + String.format(Locale.US, "%.2f", spent)
                + " of \u20B9" + String.format(Locale.US, "%.2f", budget) + " spent";
        if (combined) {
            amounts += " (\u20B9" + String.format(Locale.US, "%.2f", manualPart) + " manual + \u20B9"
                    + String.format(Locale.US, "%.2f", categoryTotal) + " categories)";
        } else if (categoryTotal > 0) {
            amounts += " (sum of category budgets)";
        }
        tvSummary.setText(over
                ? amounts + " - \u20B9" + String.format(Locale.US, "%.2f", spent - budget) + " over budget"
                : amounts + " (" + percent + "%)");
        tvSummary.setTextColor(getColor(over ? R.color.status_failed : R.color.on_surface_secondary));

        track.setVisibility(View.VISIBLE);
        fill.setBackgroundTintList(ColorStateList.valueOf(
                getColor(over ? R.color.status_failed : R.color.brand_primary)));
        track.post(() -> {
            int trackWidth = ((View) fill.getParent()).getWidth();
            android.view.ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = Math.max(4, (int) (trackWidth * (percent / 100f)));
            fill.setLayoutParams(lp);
        });
    }

    /** One row per category - {@link CategoryMeta#NAMES} plus any custom ones from
     * {@link CategoryStore}, so a manually-created category gets a row (and can have a budget
     * set on it) automatically, with no separate setup. Compares each category's confirmed
     * spend for the current month against a per-category budget from {@link BudgetStore}.
     * Independent of both the overall Monthly Budget card above (a single total) and the
     * all-time "By category" breakdown below (no month boundary, informational only). */
    private void bindCategoryBudgets() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfMonth = cal.getTimeInMillis();

        Map<String, Double> spentByCategory = new LinkedHashMap<>();
        for (Expense e : allExpenses) {
            if (e.isConfirmed() && e.createdAt >= startOfMonth) {
                spentByCategory.merge(e.category, e.amount, Double::sum);
            }
        }

        LinearLayout container = findViewById(R.id.llCategoryBudgets);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String category : CategoryStore.allCategories(this)) {
            View row = inflater.inflate(R.layout.item_category_budget, container, false);
            double spent = spentByCategory.containsKey(category) ? spentByCategory.get(category) : 0.0;
            float budget = BudgetStore.getBudget(this, category);

            ((TextView) row.findViewById(R.id.tvCbEmoji)).setText(CategoryMeta.emojiFor(category));
            ((TextView) row.findViewById(R.id.tvCbName)).setText(category);
            TextView tvAmount = row.findViewById(R.id.tvCbAmount);
            TextView tvSet = row.findViewById(R.id.tvCbSet);
            View track = row.findViewById(R.id.flCbTrack);
            View fill = row.findViewById(R.id.vCbFill);

            if (budget <= 0) {
                tvAmount.setText("\u20B9" + String.format(Locale.US, "%.2f", spent) + " spent");
                tvAmount.setTextColor(getColor(R.color.on_surface_secondary));
                tvSet.setText("Set");
                track.setVisibility(View.GONE);
            } else {
                boolean over = spent > budget;
                int percent = (int) Math.round(Math.min(1.0, spent / budget) * 100);
                tvAmount.setText("\u20B9" + String.format(Locale.US, "%.2f", spent)
                        + " / \u20B9" + String.format(Locale.US, "%.2f", budget));
                tvAmount.setTextColor(getColor(over ? R.color.status_failed : R.color.on_surface_secondary));
                tvSet.setText("Edit");
                track.setVisibility(View.VISIBLE);
                fill.setBackgroundTintList(ColorStateList.valueOf(
                        getColor(over ? R.color.status_failed : R.color.brand_primary)));
                track.post(() -> {
                    int trackWidth = ((View) fill.getParent()).getWidth();
                    android.view.ViewGroup.LayoutParams lp = fill.getLayoutParams();
                    lp.width = Math.max(4, (int) (trackWidth * (percent / 100f)));
                    fill.setLayoutParams(lp);
                });
            }

            View.OnClickListener open = v -> showSetCategoryBudgetDialog(category);
            tvSet.setOnClickListener(open);
            row.setOnClickListener(open);
            container.addView(row);
        }
    }

    /** Set/edit/clear the monthly budget for one specific category - stored in
     * {@link BudgetStore}, keyed by the category's display name so it works the same for a
     * default category or one the user typed in themselves. */
    private void showSetCategoryBudgetDialog(String category) {
        float current = BudgetStore.getBudget(this, category);
        TextInputEditText input = new TextInputEditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        if (current > 0) input.setText(String.format(Locale.US, "%.2f", current));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(CategoryMeta.emojiFor(category) + " " + category + " budget")
                .setMessage("Monthly spending target for this category (confirmed expenses only).")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        float value = Float.parseFloat(input.getText().toString().trim());
                        if (value <= 0) throw new NumberFormatException();
                        BudgetStore.setBudget(this, category, value);
                        bindCategoryBudgets();
                        bindBudget();
                    } catch (NumberFormatException ex) {
                        Toast.makeText(this, "Enter a valid amount.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null);
        if (current > 0) {
            builder.setNeutralButton("Clear", (d, w) -> {
                BudgetStore.clearBudget(this, category);
                bindCategoryBudgets();
                bindBudget();
            });
        }
        builder.show();
    }

    /** Set/change/clear the manual overall monthly budget - this now ADDS to any per-category
     * budgets (BudgetStore) rather than being replaced by them; see bindBudget(). */
    private void showSetBudgetDialog() {
        double categoryTotal = totalCategoryBudget();
        float current = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getFloat(KEY_MONTHLY_BUDGET, -1f);
        TextInputEditText input = new TextInputEditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        if (current > 0) input.setText(String.format(Locale.US, "%.2f", current));

        String msg = "Set a spending target for this and future months (confirmed expenses only).";
        if (categoryTotal > 0) {
            msg += " This is added on top of your category budgets (currently \u20B9"
                    + String.format(Locale.US, "%.2f", categoryTotal) + ").";
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Monthly budget")
                .setMessage(msg)
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        float value = Float.parseFloat(input.getText().toString().trim());
                        if (value <= 0) throw new NumberFormatException();
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putFloat(KEY_MONTHLY_BUDGET, value).apply();
                        bindBudget();
                    } catch (NumberFormatException ex) {
                        Toast.makeText(this, "Enter a valid amount.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null);
        if (current > 0) {
            builder.setNeutralButton("Clear", (d, w) -> {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_MONTHLY_BUDGET).apply();
                bindBudget();
            });
        }
        builder.show();
    }

    private void bindSearch() {
        TextInputEditText etSearch = findViewById(R.id.etSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                currentQuery = s.toString().trim();
                applyFilter();
            }
        });
    }

    private void bindFilter() {
        ChipGroup group = findViewById(R.id.chipGroupFilter);
        group.setOnCheckedStateChangeListener((g, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipPaid) currentMode = FilterMode.PAID;
            else if (id == R.id.chipPending) currentMode = FilterMode.PENDING;
            else if (id == R.id.chipFailed) currentMode = FilterMode.FAILED;
            else currentMode = FilterMode.ALL;
            applyFilter();
        });
    }

    /** Applies both the status chip and the search box together (AND, not either/or). */
    private void applyFilter() {
        visible.clear();
        String q = currentQuery.toLowerCase(Locale.getDefault());
        for (Expense e : allExpenses) {
            boolean statusOk;
            switch (currentMode) {
                case PAID: statusOk = e.isConfirmed(); break;
                case PENDING: statusOk = e.isPending(); break;
                case FAILED: statusOk = e.isFailed(); break;
                default: statusOk = true;
            }
            if (!statusOk) continue;
            boolean queryOk = q.isEmpty()
                    || (e.merchant != null && e.merchant.toLowerCase(Locale.getDefault()).contains(q))
                    || (e.note != null && e.note.toLowerCase(Locale.getDefault()).contains(q))
                    || (e.category != null && e.category.toLowerCase(Locale.getDefault()).contains(q));
            if (queryOk) visible.add(e);
        }
        adapter.notifyDataSetChanged();
        boolean empty = visible.isEmpty();
        ((TextView) findViewById(R.id.tvEmpty)).setText(allExpenses.isEmpty()
                ? "No expenses recorded yet."
                : "No expenses match your search/filter.");
        findViewById(R.id.tvEmpty).setVisibility(empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.rvExpenses).setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void bindSummary() {
        double confirmedTotal = 0;
        int pendingCount = 0, failedCount = 0;
        for (Expense e : allExpenses) {
            if (e.isConfirmed()) confirmedTotal += e.amount;
            else if (e.isFailed()) failedCount++;
            else pendingCount++;
        }
        ((TextView) findViewById(R.id.tvTotalSpent))
                .setText("\u20B9" + String.format(Locale.US, "%.2f", confirmedTotal));
        ((TextView) findViewById(R.id.tvPendingCount)).setText(String.valueOf(pendingCount));
        ((TextView) findViewById(R.id.tvFailedCount)).setText(String.valueOf(failedCount));
        ((TextView) findViewById(R.id.tvTotalCount)).setText(String.valueOf(allExpenses.size()));
    }

    /** Confirmed-only spend per category, ordered highest-first, rendered as proportional bars. */
    private void bindCategoryBreakdown() {
        Map<String, Double> byCat = new LinkedHashMap<>();
        double confirmedTotal = 0;
        for (Expense e : allExpenses) {
            if (!e.isConfirmed()) continue;
            byCat.merge(e.category, e.amount, Double::sum);
            confirmedTotal += e.amount;
        }

        LinearLayout container = findViewById(R.id.llCategoryBreakdown);
        container.removeAllViews();

        if (byCat.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No confirmed expenses yet.");
            empty.setTextColor(getColor(R.color.on_surface_secondary));
            container.addView(empty);
            return;
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(byCat.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        double grandTotal = confirmedTotal;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Map.Entry<String, Double> entry : sorted) {
            View row = inflater.inflate(R.layout.item_category_summary, container, false);
            String category = entry.getKey();
            double amount = entry.getValue();
            int percent = grandTotal > 0 ? (int) Math.round((amount / grandTotal) * 100) : 0;
            int color = getColor(CategoryMeta.colorResFor(category));

            ((TextView) row.findViewById(R.id.tvCatEmoji)).setText(CategoryMeta.emojiFor(category));
            ((TextView) row.findViewById(R.id.tvCatName)).setText(category);
            ((TextView) row.findViewById(R.id.tvCatAmount)).setText(
                    "\u20B9" + String.format(Locale.US, "%.2f", amount) + "  (" + percent + "%)");

            View fill = row.findViewById(R.id.vCatBarFill);
            fill.setBackgroundTintList(ColorStateList.valueOf(color));
            // Stretch the fill view to `percent`% of the track via its LayoutParams weight,
            // using a 100-weight parent (the track FrameLayout's width) as the scale.
            row.post(() -> {
                View track = (View) fill.getParent();
                int trackWidth = track.getWidth();
                android.view.ViewGroup.LayoutParams lp = fill.getLayoutParams();
                lp.width = Math.max(4, (int) (trackWidth * (percent / 100f)));
                fill.setLayoutParams(lp);
            });

            container.addView(row);
        }
    }

    /** Opens the edit/delete dialog for one row, pre-filled with its current values. */
    private void showEditDialog(Expense e) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_expense, null);
        TextView tvUpiId = view.findViewById(R.id.tvEditUpiId);
        TextInputEditText etMerchant = view.findViewById(R.id.etEditMerchant);
        TextInputEditText etAmount = view.findViewById(R.id.etEditAmount);
        TextInputEditText etNote = view.findViewById(R.id.etEditNote);
        Spinner spCategory = view.findViewById(R.id.spEditCategory);

        tvUpiId.setText(TextUtils.isEmpty(e.upiId) ? "No UPI ID on record" : "UPI ID: " + e.upiId);
        etMerchant.setText(e.merchant);
        etAmount.setText(String.format(Locale.US, "%.2f", e.amount));
        etNote.setText(e.note);

        List<String> categories = new ArrayList<>(CategoryStore.allCategories(this));
        if (!categories.contains(e.category)) categories.add(e.category); // keep an old/removed category visible/selectable
        spCategory.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, categories));
        int idx = categories.indexOf(e.category);
        spCategory.setSelection(Math.max(0, idx));

        new MaterialAlertDialogBuilder(this)
                .setTitle("Edit expense")
                .setView(view)
                .setPositiveButton("Save", (d, w) -> {
                    String merchant = etMerchant.getText().toString().trim();
                    String amountStr = etAmount.getText().toString().trim();
                    String note = etNote.getText().toString().trim();
                    String category = (String) spCategory.getSelectedItem();

                    if (merchant.isEmpty()) {
                        Toast.makeText(this, "Payee name can't be empty.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double amount;
                    try {
                        amount = Double.parseDouble(amountStr);
                        if (amount <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException ex) {
                        Toast.makeText(this, "Enter a valid amount.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    db.update(e.id, merchant, e.upiId, amount, note, category);
                    // The expense row itself doesn't touch account balances (see
                    // ExpenseDbHelper#update's javadoc) - only a *confirmed* expense linked to
                    // an account was ever debited in the first place, so only that case needs
                    // the balance corrected by the difference between old and new amount.
                    if (e.isConfirmed() && e.accountId != null && amount != e.amount) {
                        db.adjustAccountBalance(e.accountId, e.amount - amount);
                    }
                    reloadAndRefresh();
                    Toast.makeText(this, "Expense updated.", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Delete", (d, w) -> confirmDelete(e))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(Expense e) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete this expense?")
                .setMessage("\u20B9" + String.format(Locale.US, "%.2f", e.amount) + " to "
                        + e.merchant + ". This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    // Credit the linked account back before removing the row - only a
                    // confirmed expense was ever debited (see ExpenseDbHelper#insert/
                    // updateStatus), so a pending/failed one needs no reversal.
                    if (e.isConfirmed() && e.accountId != null) {
                        db.adjustAccountBalance(e.accountId, e.amount);
                    }
                    db.delete(e.id);
                    reloadAndRefresh();
                    Toast.makeText(this, "Expense deleted.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
