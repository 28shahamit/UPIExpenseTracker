package com.expensetracker.upi;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Home / dashboard screen. This used to also contain the entire pay-and-log flow
 * (QR scan, manual entry, amount/note/category, Pay/Log buttons all in one long
 * scrolling form) - that flow is unchanged in behavior but now lives in its own
 * screen, PaymentActivity, reached via the "New payment" / "Log an expense" cards
 * below. This class only shows a summary and routes to the other screens; it does
 * not read or modify any payment/QR/account data beyond the read-only display
 * below, so payment logic itself is untouched.
 */
public class MainActivity extends BaseActivity {

    private TextView tvAccountBanner, tvPendingCount, tvMonthSpend;
    private ExpenseDbHelper db;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new ExpenseDbHelper(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        tvAccountBanner = findViewById(R.id.tvAccountBanner);
        tvPendingCount = findViewById(R.id.tvPendingCount);
        tvMonthSpend = findViewById(R.id.tvMonthSpend);

        findViewById(R.id.btnManageAccounts).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, AccountsActivity.class)));
        findViewById(R.id.cardAccounts).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, AccountsActivity.class)));
        findViewById(R.id.cardHistory).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, HistoryActivity.class)));
        findViewById(R.id.cardNewPayment).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, PaymentActivity.class)));
        findViewById(R.id.cardLogExpense).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, PaymentActivity.class);
            intent.putExtra("mode", "log");
            startActivity(intent);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refreshSummary();
    }

    /** Read-only snapshot for the dashboard: which account expenses are billed to and
     * its balance (same logic PaymentActivity uses), plus how many expenses are still
     * pending confirmation and how much has been confirmed as spent this calendar
     * month. Recomputed on every resume since a payment made in PaymentActivity, or an
     * edit made in HistoryActivity/AccountsActivity, can change all of these. */
    private void refreshSummary() {
        Account account = db.getDefaultAccount();
        if (account == null) {
            tvAccountBanner.setText("No account set up");
            tvAccountBanner.setTextColor(getColor(android.R.color.white));
        } else {
            String balanceStr = String.format(Locale.US, "%.2f", account.balance);
            tvAccountBanner.setText(account.name + " \u00B7 \u20B9" + balanceStr);
        }

        List<Expense> expenses = db.getAllExpenses();
        int pending = 0;
        double monthSpend = 0;
        Calendar now = Calendar.getInstance();
        int thisYear = now.get(Calendar.YEAR);
        int thisMonth = now.get(Calendar.MONTH);
        Calendar rowCal = Calendar.getInstance();
        for (Expense e : expenses) {
            if (e.isPending()) pending++;
            if (e.isConfirmed()) {
                rowCal.setTimeInMillis(e.createdAt);
                if (rowCal.get(Calendar.YEAR) == thisYear && rowCal.get(Calendar.MONTH) == thisMonth) {
                    monthSpend += e.amount;
                }
            }
        }
        tvPendingCount.setText(String.valueOf(pending));
        tvMonthSpend.setText("\u20B9" + String.format(Locale.US, "%.0f", monthSpend));
    }
}
