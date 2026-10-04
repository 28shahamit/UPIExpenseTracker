package com.expensetracker.upi;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import java.util.List;
import java.util.Locale;

/**
 * Read-only "why is my balance this number" view for one account - every row is a
 * LedgerEntry written by ExpenseDbHelper#adjustAccountBalance, newest first, each showing
 * what happened, the signed amount, and the running balance right after. Also the entry
 * point for recording a balance change that isn't a payment (manual correction, or money
 * credited/debited outside this app) via showChangeBalanceDialog() below.
 */
public class BalanceHistoryActivity extends AppCompatActivity {

    private ExpenseDbHelper db;
    private long accountId;
    private String accountName;
    private TextView tvCurrentBalance, tvEmpty;
    private RecyclerView rvLedger;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_balance_history);

        accountId = getIntent().getLongExtra("accountId", -1);
        accountName = getIntent().getStringExtra("accountName");
        db = new ExpenseDbHelper(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(accountName != null ? accountName + " \u2013 History" : "Balance History");
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvCurrentBalance = findViewById(R.id.tvCurrentBalance);
        tvEmpty = findViewById(R.id.tvEmpty);
        rvLedger = findViewById(R.id.rvLedger);
        rvLedger.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btnChangeBalance).setOnClickListener(v -> showChangeBalanceDialog());

        refresh();
    }

    private void refresh() {
        List<LedgerEntry> entries = db.getLedger(accountId);
        tvCurrentBalance.setText("Current balance: " + Money.format(db.getAccountBalance(accountId)));

        boolean empty = entries.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvLedger.setVisibility(empty ? View.GONE : View.VISIBLE);
        rvLedger.setAdapter(new LedgerAdapter(entries));
    }

    /**
     * "Account Balance → Change Balance": three mutually exclusive ways to move a balance
     * that aren't a payment made through this app.
     * - Manual correction (default): reconciling against your real bank statement - you type
     *   the new balance directly and the difference is logged.
     * - Money credited / debited: an actual inflow/outflow (salary, cash withdrawal, etc.)
     *   that happened outside the app - you type the amount and where it went, and the
     *   ledger reads like a normal transaction ("Salary credited +₹5,000") rather than a
     *   correction.
     * All three ultimately call adjustAccountBalance() so the balance and ledger stay in sync
     * exactly as for every other change in the app - see that method's javadoc.
     */
    private void showChangeBalanceDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_change_balance, null);
        RadioGroup rg = view.findViewById(R.id.rgChangeType);
        View llCorrection = view.findViewById(R.id.llCorrection);
        View llCredit = view.findViewById(R.id.llCredit);
        View llDebit = view.findViewById(R.id.llDebit);

        TextView tvCurrentBalanceLabel = view.findViewById(R.id.tvCurrentBalanceLabel);
        TextInputEditText etNewBalance = view.findViewById(R.id.etNewBalance);
        TextInputEditText etCorrectionNote = view.findViewById(R.id.etCorrectionNote);

        TextInputEditText etCreditAmount = view.findViewById(R.id.etCreditAmount);
        TextInputEditText etCreditSource = view.findViewById(R.id.etCreditSource);
        TextInputEditText etCreditRef = view.findViewById(R.id.etCreditRef);
        TextInputEditText etCreditNote = view.findViewById(R.id.etCreditNote);

        TextInputEditText etDebitAmount = view.findViewById(R.id.etDebitAmount);
        TextInputEditText etDebitDest = view.findViewById(R.id.etDebitDest);
        TextInputEditText etDebitRef = view.findViewById(R.id.etDebitRef);
        TextInputEditText etDebitNote = view.findViewById(R.id.etDebitNote);

        double currentBalance = db.getAccountBalance(accountId);
        tvCurrentBalanceLabel.setText("Current balance: " + Money.format(currentBalance));
        etNewBalance.setText(String.format(Locale.US, "%.2f", currentBalance));

        rg.setOnCheckedChangeListener((group, checkedId) -> {
            llCorrection.setVisibility(checkedId == R.id.rbCorrection ? View.VISIBLE : View.GONE);
            llCredit.setVisibility(checkedId == R.id.rbCredit ? View.VISIBLE : View.GONE);
            llDebit.setVisibility(checkedId == R.id.rbDebit ? View.VISIBLE : View.GONE);
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle("Change balance")
                .setView(view)
                .setPositiveButton("Save", (d, w) -> {
                    int checked = rg.getCheckedRadioButtonId();
                    if (checked == R.id.rbCredit) {
                        saveCredit(etCreditAmount, etCreditSource, etCreditRef, etCreditNote);
                    } else if (checked == R.id.rbDebit) {
                        saveDebit(etDebitAmount, etDebitDest, etDebitRef, etDebitNote);
                    } else {
                        saveCorrection(currentBalance, etNewBalance, etCorrectionNote);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private Double parsePositiveAmount(TextInputEditText field) {
        try {
            double amount = Double.parseDouble(field.getText().toString().trim());
            if (amount <= 0) throw new NumberFormatException();
            return amount;
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "Enter a valid amount.", Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    private void saveCredit(TextInputEditText etAmount, TextInputEditText etSource,
                             TextInputEditText etRef, TextInputEditText etNote) {
        Double amount = parsePositiveAmount(etAmount);
        if (amount == null) return;
        String source = etSource.getText().toString().trim();
        if (source.isEmpty()) {
            Toast.makeText(this, "Enter where this was credited from.", Toast.LENGTH_SHORT).show();
            return;
        }
        String ref = etRef.getText().toString().trim();
        String note = etNote.getText().toString().trim();
        String reason = source + " credited"
                + (!note.isEmpty() ? " - " + note : "")
                + (!ref.isEmpty() ? " (Ref: " + ref + ")" : "");
        db.adjustAccountBalance(accountId, amount, reason, null, "CREDIT");
        refresh();
        Toast.makeText(this, "Credit recorded.", Toast.LENGTH_SHORT).show();
    }

    private void saveDebit(TextInputEditText etAmount, TextInputEditText etDest,
                            TextInputEditText etRef, TextInputEditText etNote) {
        Double amount = parsePositiveAmount(etAmount);
        if (amount == null) return;
        String dest = etDest.getText().toString().trim();
        if (dest.isEmpty()) {
            Toast.makeText(this, "Enter where this was debited to.", Toast.LENGTH_SHORT).show();
            return;
        }
        String ref = etRef.getText().toString().trim();
        String note = etNote.getText().toString().trim();
        String reason = dest + " debited"
                + (!note.isEmpty() ? " - " + note : "")
                + (!ref.isEmpty() ? " (Ref: " + ref + ")" : "");
        db.adjustAccountBalance(accountId, -amount, reason, null, "DEBIT");
        refresh();
        Toast.makeText(this, "Debit recorded.", Toast.LENGTH_SHORT).show();
    }

    private void saveCorrection(double currentBalance, TextInputEditText etNewBalance, TextInputEditText etNote) {
        double newBalance;
        try {
            newBalance = Double.parseDouble(etNewBalance.getText().toString().trim());
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "Enter a valid balance.", Toast.LENGTH_SHORT).show();
            return;
        }
        double delta = newBalance - currentBalance;
        if (delta == 0) {
            Toast.makeText(this, "That's already the current balance.", Toast.LENGTH_SHORT).show();
            return;
        }
        String note = etNote.getText().toString().trim();
        String reason = "Manual correction" + (!note.isEmpty() ? ": " + note : "");
        db.adjustAccountBalance(accountId, delta, reason, null, "CORRECTION");
        refresh();
        Toast.makeText(this, "Balance corrected.", Toast.LENGTH_SHORT).show();
    }
}
