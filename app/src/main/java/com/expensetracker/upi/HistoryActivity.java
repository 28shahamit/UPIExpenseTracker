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
import android.widget.Button;
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
    private long dateStart = -1, dateEnd = -1; // -1 = no date filter

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
        bindDateFilter();
        findViewById(R.id.tvEditBudget).setOnClickListener(v -> showSetBudgetDialog());
        findViewById(R.id.llAlloc).setOnClickListener(v -> showSetBudgetDialog());
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
        int daysSinceWeekStart = (weekCal.get(Calendar.DAY_OF_WEEK) - weekCal.getFirstDayOfWeek() + 7) % 7;
        weekCal.add(Calendar.DAY_OF_MONTH, -daysSinceWeekStart);
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

    /** Sum of all set per-category budgets, used only for the informational warning. */
    private double totalCategoryBudget() {
        double sum = 0;
        for (String category : CategoryStore.allCategories(this)) {
            float b = BudgetStore.getBudget(this, category);
            if (b > 0) sum += b;
        }
        return sum;
    }

    private static String money(double d) {
        return "\u20B9" + (d == Math.rint(d)
                ? String.format(new Locale("en", "IN"), "%,.0f", d)
                : String.format(new Locale("en", "IN"), "%,.2f", d));
    }

    private long monthStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private void fillBar(View track, View fill, int percent, boolean red) {
        track.setVisibility(View.VISIBLE);
        fill.setBackgroundTintList(ColorStateList.valueOf(
                getColor(red ? R.color.status_failed : R.color.brand_primary)));
        track.post(() -> {
            int w = ((View) fill.getParent()).getWidth();
            android.view.ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = Math.max(4, (int) (w * (Math.min(100, percent) / 100f)));
            fill.setLayoutParams(lp);
        });
    }

    /** Monthly summary + allocation check. Monthly limit is independent of category limits;
     * spend = every confirmed expense this month. Category totals only drive the warning. */
    private void bindBudget() {
        float manual = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getFloat(KEY_MONTHLY_BUDGET, -1f);
        long start = monthStart();
        double spent = 0;
        for (Expense e : allExpenses) if (e.isConfirmed() && e.createdAt >= start) spent += e.amount;

        ((TextView) findViewById(R.id.tvEditBudget)).setText(manual > 0 ? "Edit monthly \u203A" : "Set monthly \u203A");
        ((TextView) findViewById(R.id.tvBudgetSpent)).setText(money(spent));
        TextView tvLimit = findViewById(R.id.tvBudgetLimit);
        TextView tvPct = findViewById(R.id.tvBudgetPct);
        TextView tvRem = findViewById(R.id.tvBudgetRemaining);
        View track = findViewById(R.id.flBudgetTrack);
        View alloc = findViewById(R.id.llAlloc);

        if (manual <= 0) {
            tvLimit.setText("Not set");
            tvLimit.setTextColor(getColor(R.color.on_surface_secondary));
            tvLimit.setTextSize(22);
            tvPct.setText("this month");
            track.setVisibility(View.GONE);
            tvRem.setVisibility(View.GONE);
            alloc.setVisibility(View.GONE);
            return;
        }
        tvLimit.setText(money(manual));
        tvLimit.setTextColor(getColor(R.color.on_surface));
        tvLimit.setTextSize(30);
        boolean over = spent > manual;
        int percent = (int) Math.round(spent / manual * 100);
        tvPct.setText(percent + "% used");
        fillBar(track, findViewById(R.id.vBudgetFill), percent, over);
        tvRem.setVisibility(View.VISIBLE);
        tvRem.setText(over ? money(spent - manual) + " over budget" : money(manual - spent) + " remaining");
        tvRem.setTextColor(getColor(over ? R.color.status_failed : R.color.on_surface_secondary));

        double cats = totalCategoryBudget();
        if (cats > manual) {
            ((TextView) findViewById(R.id.tvAllocBody)).setText("Your category budgets total " + money(cats)
                    + ", which is " + money(cats - manual) + " more than your monthly budget.");
            ((TextView) findViewById(R.id.tvAllocMonthly)).setText(money(manual));
            ((TextView) findViewById(R.id.tvAllocCats)).setText(money(cats));
            alloc.setVisibility(View.VISIBLE);
        } else {
            alloc.setVisibility(View.GONE);
        }
    }

    private boolean showUnsetCategories = false;

    /** Category rows (icon, spent of limit, bar, %, Edit/Set) + total allocated + quick insights. */
    private void bindCategoryBudgets() {
        long start = monthStart();
        Map<String, Double> spentBy = new LinkedHashMap<>();
        for (Expense e : allExpenses) {
            if (e.isConfirmed() && e.createdAt >= start) spentBy.merge(e.category, e.amount, Double::sum);
        }
        LinearLayout container = findViewById(R.id.llCategoryBudgets);
        container.removeAllViews();
        LinearLayout unsetBox = findViewById(R.id.llCategoryUnset);
        unsetBox.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        int overCount = 0, withinCount = 0, noneCount = 0;
        double allocated = 0;
        for (String category : CategoryStore.allCategories(this)) {
            View row = inflater.inflate(R.layout.item_category_budget, container, false);
            double spent = spentBy.containsKey(category) ? spentBy.get(category) : 0.0;
            float budget = BudgetStore.getBudget(this, category);
            ((TextView) row.findViewById(R.id.tvCbEmoji)).setText(CategoryMeta.emojiFor(category));
            ((TextView) row.findViewById(R.id.tvCbName)).setText(category);
            TextView tvAmount = row.findViewById(R.id.tvCbAmount);
            TextView tvPct = row.findViewById(R.id.tvCbPct);
            TextView tvSet = row.findViewById(R.id.tvCbSet);
            View track = row.findViewById(R.id.flCbTrack);
            if (budget <= 0) {
                noneCount++;
                tvAmount.setText(money(spent) + " spent \u00B7 No budget set");
                tvPct.setText("-");
                tvPct.setTextColor(getColor(R.color.on_surface_secondary));
                tvSet.setText("Set");
                track.setVisibility(View.INVISIBLE);
            } else {
                allocated += budget;
                boolean over = spent > budget;
                if (over) overCount++; else withinCount++;
                int percent = (int) Math.round(spent / budget * 100);
                tvAmount.setText(money(spent) + " of " + money(budget));
                tvPct.setText(percent + "%");
                tvPct.setTextColor(getColor(percent >= 75 ? R.color.status_failed : R.color.on_surface));
                tvSet.setText("Edit");
                fillBar(track, row.findViewById(R.id.vCbFill), percent, over);
            }
            row.setOnClickListener(v -> showSetCategoryBudgetDialog(category));
            (budget > 0 ? container : unsetBox).addView(row);
        }
        // Rows with a budget stay visible; "no budget" rows collapse behind a toggle -
        // unless nothing has a budget yet, when everything shows so each can be set.
        TextView more = findViewById(R.id.tvCbMore);
        if (withinCount + overCount == 0 || noneCount == 0) {
            unsetBox.setVisibility(View.VISIBLE);
            more.setVisibility(View.GONE);
        } else {
            unsetBox.setVisibility(showUnsetCategories ? View.VISIBLE : View.GONE);
            more.setVisibility(View.VISIBLE);
            more.setText(showUnsetCategories ? "Hide categories without budget \u25B4"
                    : "Show " + noneCount + " more without budget \u25BE");
            more.setOnClickListener(v -> { showUnsetCategories = !showUnsetCategories; bindCategoryBudgets(); });
        }
        ((TextView) findViewById(R.id.tvCatTotalAllocated)).setText("Total allocated: " + money(allocated));
        ((TextView) findViewById(R.id.tvInsOver)).setText(String.valueOf(overCount));
        ((TextView) findViewById(R.id.tvInsWithin)).setText(String.valueOf(withinCount));
        ((TextView) findViewById(R.id.tvInsNone)).setText(String.valueOf(noneCount));
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
        float current = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getFloat(KEY_MONTHLY_BUDGET, -1f);
        TextInputEditText input = new TextInputEditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        if (current > 0) input.setText(String.format(Locale.US, "%.2f", current));

        String msg = "Optional monthly spending limit (confirmed expenses). Independent of category limits.";

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Monthly limit")
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

    /** Lets the person narrow the list to expenses created on one calendar day. */
    private void bindDateFilter() {
        Button btnPick = findViewById(R.id.btnPickDate);
        Button btnClear = findViewById(R.id.btnClearDate);
        btnPick.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            if (dateStart != -1) cal.setTimeInMillis(dateStart);
            new android.app.DatePickerDialog(this, (picker, year, month, day) -> {
                Calendar start = Calendar.getInstance();
                start.set(year, month, day, 0, 0, 0);
                start.set(Calendar.MILLISECOND, 0);
                dateStart = start.getTimeInMillis();
                dateEnd = dateStart + 24L * 60 * 60 * 1000 - 1;
                btnPick.setText("📅 " + String.format(Locale.US, "%02d/%02d/%04d", day, month + 1, year));
                btnClear.setVisibility(View.VISIBLE);
                applyFilter();
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });
        btnClear.setOnClickListener(v -> {
            dateStart = -1; dateEnd = -1;
            btnPick.setText("📅 Filter by date");
            btnClear.setVisibility(View.GONE);
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
            if (dateStart != -1 && (e.createdAt < dateStart || e.createdAt > dateEnd)) continue;
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

        // Sum of whatever's currently visible - only shown once a search/date/status
        // filter actually narrows the list, since with nothing applied it just repeats
        // the "Confirmed spend" summary card above.
        boolean filtering = !q.isEmpty() || dateStart != -1 || currentMode != FilterMode.ALL;
        TextView tvFilteredTotal = findViewById(R.id.tvFilteredTotal);
        if (filtering && !empty) {
            double sum = 0;
            for (Expense e : visible) sum += e.amount;
            tvFilteredTotal.setText(visible.size() + (visible.size() == 1 ? " result · " : " results · ")
                    + "\u20B9" + String.format(Locale.US, "%.2f", sum));
            tvFilteredTotal.setVisibility(View.VISIBLE);
        } else {
            tvFilteredTotal.setVisibility(View.GONE);
        }
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

        String upiLine = TextUtils.isEmpty(e.upiId) ? "No UPI ID on record" : "UPI ID: " + e.upiId;
        String accountName = db.getAccountName(e.accountId);
        tvUpiId.setText(accountName != null ? upiLine + "\nPaid from: " + accountName : upiLine);
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
                        db.adjustAccountBalance(e.accountId, e.amount - amount, "Edited expense: " + merchant, e.id, "EXPENSE");
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
                        db.adjustAccountBalance(e.accountId, e.amount, "Deleted expense: " + e.merchant, e.id, "EXPENSE");
                    }
                    db.delete(e.id);
                    reloadAndRefresh();
                    Toast.makeText(this, "Expense deleted.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
