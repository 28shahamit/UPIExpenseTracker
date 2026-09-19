package com.expensetracker.upi;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
public class HistoryActivity extends BaseActivity {

    private ExpenseDbHelper db;
    private List<Expense> allExpenses;
    private ExpenseAdapter adapter;
    private final List<Expense> visible = new ArrayList<>();
    private FilterMode currentMode = FilterMode.ALL;
    private String currentQuery = "";
    private PeriodMode currentPeriod = PeriodMode.ALL_TIME;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

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
        bindPeriod();
        reloadAndRefresh();
    }

    private enum FilterMode { ALL, PAID, PENDING, FAILED }

    /** Scopes the Dashboard's summary card + category breakdown to today / this calendar week
     * (Sunday-start, matching the device's Calendar) / this calendar month / all time. Does
     * NOT affect the transaction list below, which keeps its own status+search filtering
     * (see applyFilter()) independent of the dashboard's time window. */
    private enum PeriodMode { TODAY, WEEK, MONTH, ALL_TIME }

    /** Re-reads every row from the DB and rebinds every section of the screen - called on
     * first load and after any edit/delete so the summary/breakdown/list all stay consistent. */
    private void reloadAndRefresh() {
        allExpenses = db.getAllExpenses();
        List<Expense> periodScoped = applyPeriod(allExpenses);
        bindSummary(periodScoped);
        bindCategoryBreakdown(periodScoped);
        applyFilter();
    }

    private void bindPeriod() {
        ChipGroup group = findViewById(R.id.chipGroupPeriod);
        group.setOnCheckedStateChangeListener((g, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipToday) currentPeriod = PeriodMode.TODAY;
            else if (id == R.id.chipWeek) currentPeriod = PeriodMode.WEEK;
            else if (id == R.id.chipMonth) currentPeriod = PeriodMode.MONTH;
            else currentPeriod = PeriodMode.ALL_TIME;

            String label;
            switch (currentPeriod) {
                case TODAY: label = "Confirmed spend \u00B7 today"; break;
                case WEEK: label = "Confirmed spend \u00B7 this week"; break;
                case MONTH: label = "Confirmed spend \u00B7 this month"; break;
                default: label = "Confirmed spend \u00B7 all time";
            }
            ((TextView) findViewById(R.id.tvSummaryLabel)).setText(label);

            List<Expense> periodScoped = applyPeriod(allExpenses);
            bindSummary(periodScoped);
            bindCategoryBreakdown(periodScoped);
        });
    }

    /** Start-of-day boundary for "today"/"this week"/"this month", using the device's default
     * time zone/locale/week-start convention (whatever Calendar.getInstance() resolves to). */
    private List<Expense> applyPeriod(List<Expense> source) {
        if (currentPeriod == PeriodMode.ALL_TIME) return source;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (currentPeriod == PeriodMode.WEEK) {
            cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
        } else if (currentPeriod == PeriodMode.MONTH) {
            cal.set(Calendar.DAY_OF_MONTH, 1);
        }
        long boundary = cal.getTimeInMillis();

        List<Expense> result = new ArrayList<>();
        for (Expense e : source) {
            if (e.createdAt >= boundary) result.add(e);
        }
        return result;
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

    private void bindSummary(List<Expense> scoped) {
        double confirmedTotal = 0;
        int pendingCount = 0, failedCount = 0;
        for (Expense e : scoped) {
            if (e.isConfirmed()) confirmedTotal += e.amount;
            else if (e.isFailed()) failedCount++;
            else pendingCount++;
        }
        ((TextView) findViewById(R.id.tvTotalSpent))
                .setText("\u20B9" + String.format(Locale.US, "%.2f", confirmedTotal));
        ((TextView) findViewById(R.id.tvPendingCount)).setText(String.valueOf(pendingCount));
        ((TextView) findViewById(R.id.tvFailedCount)).setText(String.valueOf(failedCount));
        ((TextView) findViewById(R.id.tvTotalCount)).setText(String.valueOf(scoped.size()));
    }

    /** Confirmed-only spend per category within the current period, ordered highest-first,
     * rendered as proportional bars. */
    private void bindCategoryBreakdown(List<Expense> scoped) {
        Map<String, Double> byCat = new LinkedHashMap<>();
        double confirmedTotal = 0;
        for (Expense e : scoped) {
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

        TextView tvDetails = view.findViewById(R.id.tvViewRequestDetails);
        if (e.hasRequestDetails()) {
            tvDetails.setVisibility(View.VISIBLE);
            tvDetails.setOnClickListener(v -> showRequestDetailsDialog(e));
        }

        List<String> categories = new ArrayList<>(CategoryStore.allCategories(this));
        if (!categories.contains(e.category)) categories.add(e.category); // keep an old/removed category visible/selectable
        spCategory.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, categories));
        int idx = categories.indexOf(e.category);
        spCategory.setSelection(Math.max(0, idx));

        new AlertDialog.Builder(this)
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
                    reloadAndRefresh();
                    Toast.makeText(this, "Expense updated.", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Delete", (d, w) -> confirmDelete(e))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Read-only dump of exactly what left the device for this payment attempt, plus whatever
     * the PSP reported back - see ExpenseDbHelper#logOutgoingRequest()/updateStatus(). Meant
     * for troubleshooting a decline without needing adb/Logcat: e.g. a "payment mode not
     * allowed for this UPI ID" failure is something to check field-by-field against what's
     * shown here, not something this app's request can be tuned to avoid (some VPAs - most
     * often soundbox/QR-issued ones - are locked server-side to live-scan-only; see
     * PaymentActivity#openUpiAppToPayManually() for the workaround: open the payee's own app
     * and scan there directly instead of via this dialog's link). */
    private void showRequestDetailsDialog(Expense e) {
        StringBuilder sb = new StringBuilder();
        sb.append("Expense #").append(e.id).append(" \u00B7 \u20B9")
                .append(String.format(Locale.US, "%.2f", e.amount)).append(" to ").append(e.merchant).append('\n');
        sb.append("Status: ").append(e.status).append("\n\n");
        appendDetailField(sb, "Source QR (as scanned)", e.sourceQr);
        appendDetailField(sb, "Request sent (upi://... link)", e.reqUri);
        appendDetailField(sb, "Our reference (tr)", e.reqTr);
        appendDetailField(sb, "PSP's returned reference (txn_ref)", e.txnRef);
        appendDetailField(sb, "PSP's response code", e.responseCode);
        if (TextUtils.isEmpty(e.reqUri) && !TextUtils.isEmpty(e.sourceQr)) {
            sb.append("(This QR was scanned but no request was ever sent for it - the "
                    + "attempt was likely cancelled before a UPI app was chosen.)");
        }

        TextView content = new TextView(this);
        content.setText(sb.toString().trim());
        content.setTextIsSelectable(true);
        content.setTextSize(12.5f);
        content.setPadding(48, 32, 48, 32);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
                .setTitle("Request details")
                .setView(scroll)
                .setPositiveButton("Copy all", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("UPI request details", sb.toString().trim()));
                    Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static void appendDetailField(StringBuilder sb, String label, String value) {
        if (TextUtils.isEmpty(value)) return;
        sb.append(label).append(":\n").append(value).append("\n\n");
    }

    private void confirmDelete(Expense e) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this expense?")
                .setMessage("\u20B9" + String.format(Locale.US, "%.2f", e.amount) + " to "
                        + e.merchant + ". This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    db.delete(e.id);
                    reloadAndRefresh();
                    Toast.makeText(this, "Expense deleted.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
