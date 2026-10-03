package com.expensetracker.upi;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

/**
 * Read-only "why is my balance this number" view for one account - every row is a
 * LedgerEntry written by ExpenseDbHelper#adjustAccountBalance, in reverse-chronological
 * order, each showing what happened, the signed amount, and the running balance right
 * after. Doesn't modify anything itself.
 */
public class BalanceHistoryActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_balance_history);

        long accountId = getIntent().getLongExtra("accountId", -1);
        String accountName = getIntent().getStringExtra("accountName");

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(accountName != null ? accountName + " \u2013 History" : "Balance History");
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        ExpenseDbHelper db = new ExpenseDbHelper(this);
        List<LedgerEntry> entries = db.getLedger(accountId);

        TextView tvCurrentBalance = findViewById(R.id.tvCurrentBalance);
        if (!entries.isEmpty()) {
            tvCurrentBalance.setText("Current balance: " + Money.format(entries.get(0).balanceAfter));
        } else {
            tvCurrentBalance.setText("Current balance: " + Money.format(0));
        }

        boolean empty = entries.isEmpty();
        findViewById(R.id.tvEmpty).setVisibility(empty ? View.VISIBLE : View.GONE);
        RecyclerView rv = findViewById(R.id.rvLedger);
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new LedgerAdapter(entries));
    }
}
