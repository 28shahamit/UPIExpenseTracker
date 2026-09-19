package com.expensetracker.upi;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.textfield.TextInputEditText;
import java.util.List;
import java.util.Locale;

/**
 * Add/edit/delete bank accounts and choose which one is the default that new expenses are
 * automatically billed against. Balance math itself (debit on log, refund on delete/edit,
 * negative balances allowed) lives in {@link ExpenseDbHelper} - this screen only renders the
 * current state and collects the user's name/starting-balance input.
 */
public class AccountsActivity extends BaseActivity {

    private ExpenseDbHelper db;
    private LinearLayout container;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accounts);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        db = new ExpenseDbHelper(this);
        container = findViewById(R.id.llAccounts);
        findViewById(R.id.btnAddAccount).setOnClickListener(v -> showEditAccountDialog(null));

        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh(); // balances may have changed elsewhere (e.g. an expense logged from MainActivity)
    }

    private void refresh() {
        container.removeAllViews();
        List<Account> accounts = db.getAllAccounts();

        findViewById(R.id.tvNoAccounts).setVisibility(accounts.isEmpty() ? View.VISIBLE : View.GONE);
        container.setVisibility(accounts.isEmpty() ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (Account a : accounts) {
            View row = inflater.inflate(R.layout.item_account, container, false);

            ((TextView) row.findViewById(R.id.tvAccountName)).setText(a.name);
            TextView tvBalance = row.findViewById(R.id.tvAccountBalance);
            tvBalance.setText("\u20B9" + String.format(Locale.US, "%.2f", a.balance));
            tvBalance.setTextColor(getColor(a.balance < 0 ? R.color.status_failed : R.color.on_surface));

            row.findViewById(R.id.tvDefaultBadge).setVisibility(a.isDefault ? View.VISIBLE : View.GONE);

            Button btnSetDefault = row.findViewById(R.id.btnSetDefault);
            if (a.isDefault) {
                btnSetDefault.setVisibility(View.GONE);
            } else {
                btnSetDefault.setVisibility(View.VISIBLE);
                btnSetDefault.setOnClickListener(v -> {
                    db.setDefaultAccount(a.id);
                    Toast.makeText(this, a.name + " is now the default account.", Toast.LENGTH_SHORT).show();
                    refresh();
                });
            }

            row.findViewById(R.id.btnEditAccount).setOnClickListener(v -> showEditAccountDialog(a));
            row.findViewById(R.id.btnDeleteAccount).setOnClickListener(v -> confirmDelete(a));

            container.addView(row);
        }
    }

    /** account == null means "add new"; otherwise pre-fills and updates that account. */
    private void showEditAccountDialog(Account account) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_account, null);
        TextInputEditText etName = view.findViewById(R.id.etAccountName);
        TextInputEditText etBalance = view.findViewById(R.id.etAccountBalance);

        if (account != null) {
            etName.setText(account.name);
            etBalance.setText(String.format(Locale.US, "%.2f", account.balance));
        }

        new AlertDialog.Builder(this)
                .setTitle(account == null ? "Add bank account" : "Edit account")
                .setView(view)
                .setPositiveButton(account == null ? "Add" : "Save", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String balanceStr = etBalance.getText().toString().trim().replace(',', '.');
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter an account name.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double balance;
                    try {
                        balance = balanceStr.isEmpty() ? 0 : Double.parseDouble(balanceStr);
                    } catch (NumberFormatException ex) {
                        Toast.makeText(this, "Enter a valid balance.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (account == null) {
                        db.insertAccount(name, balance);
                        Toast.makeText(this, "Account added.", Toast.LENGTH_SHORT).show();
                    } else {
                        db.updateAccount(account.id, name, balance);
                        Toast.makeText(this, "Account updated.", Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(Account a) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this account?")
                .setMessage("\"" + a.name + "\" will be removed. Past expenses billed to it stay in your "
                        + "history but will no longer be tied to any account.")
                .setPositiveButton("Delete", (d, w) -> {
                    db.deleteAccount(a.id);
                    Toast.makeText(this, "Account deleted.", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
