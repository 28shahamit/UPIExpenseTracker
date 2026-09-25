package com.expensetracker.upi;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Add/rename/rebalance/delete bank accounts and choose which one is the default that every
 * payment, "already paid elsewhere" log, and open-UPI-app flow in MainActivity auto-attaches
 * new expenses to (see MainActivity#getDefaultAccountId()). Balances themselves are updated
 * automatically elsewhere (ExpenseDbHelper#insert/updateStatus on confirm, HistoryActivity on
 * edit/delete) - this screen only lets the user correct a balance directly (reconciliation)
 * or start one from scratch.
 */
public class AccountsActivity extends AppCompatActivity {

    private ExpenseDbHelper db;
    private LinearLayout llAccounts;
    private TextView tvNoAccounts;

    // Backup/restore: copies the raw SQLite file via Storage Access Framework so a full
    // reinstall (common while iterating on builds) doesn't lose accounts/expenses/budgets.
    private final ActivityResultLauncher<String> backupLauncher =
        registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
            if (uri != null) doBackup(uri);
        });
    private final ActivityResultLauncher<String[]> restoreLauncher =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) confirmRestore(uri);
        });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accounts);
        BottomNav.wire(this, R.id.navAccounts);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        db = new ExpenseDbHelper(this);
        llAccounts = findViewById(R.id.llAccounts);
        tvNoAccounts = findViewById(R.id.tvNoAccounts);
        findViewById(R.id.btnAddAccount).setOnClickListener(v -> showAddAccountDialog());
        findViewById(R.id.btnBackup).setOnClickListener(v ->
            backupLauncher.launch("upi_expense_backup.db"));
        findViewById(R.id.btnRestore).setOnClickListener(v ->
            restoreLauncher.launch(new String[]{"*/*"}));

        refresh();
    }

    private void doBackup(Uri dest) {
        try {
            db.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(FULL)", null).close();
            try (InputStream in = new FileInputStream(getDatabasePath("expenses.db"));
                 OutputStream out = getContentResolver().openOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Toast.makeText(this, "Backup saved.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRestore(Uri src) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("Restore backup?")
            .setMessage("This replaces all current accounts, expenses, categories and budgets with the backup file. This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore", (d, w) -> doRestore(src))
            .show();
    }

    private void doRestore(Uri src) {
        try {
            db.close();
            File dbFile = getDatabasePath("expenses.db");
            try (InputStream in = getContentResolver().openInputStream(src);
                 OutputStream out = new FileOutputStream(dbFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            // Drop any stale WAL/SHM journals so the restored file is read as-is.
            new File(dbFile.getPath() + "-wal").delete();
            new File(dbFile.getPath() + "-shm").delete();
            db = new ExpenseDbHelper(this);
            Toast.makeText(this, "Backup restored.", Toast.LENGTH_SHORT).show();
            refresh();
        } catch (Exception e) {
            Toast.makeText(this, "Restore failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        llAccounts.removeAllViews();
        List<Account> accounts = db.getAllAccounts();
        tvNoAccounts.setVisibility(accounts.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (Account a : accounts) {
            View row = inflater.inflate(R.layout.item_account, llAccounts, false);
            ((TextView) row.findViewById(R.id.tvAccName)).setText(a.name);
            ((TextView) row.findViewById(R.id.tvAccBalance))
                    .setText("\u20B9" + String.format(Locale.US, "%.2f", a.balance));
            row.findViewById(R.id.tvAccDefaultChip).setVisibility(a.isDefault ? View.VISIBLE : View.GONE);

            // Tapping the row itself sets it as default (the common action); the edit icon
            // opens rename/rebalance/delete (the occasional one) - mirrors the category
            // spinner's tap-vs-long-press-equivalent split elsewhere in the app.
            row.setOnClickListener(v -> {
                if (a.isDefault) return;
                db.setDefaultAccount(a.id);
                Toast.makeText(this, a.name + " is now your default account.", Toast.LENGTH_SHORT).show();
                refresh();
            });
            row.findViewById(R.id.btnAccMenu).setOnClickListener(v -> showEditAccountDialog(a));

            llAccounts.addView(row);
        }
    }

    private void showAddAccountDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_account, null);
        EditText etName = view.findViewById(R.id.etAccName);
        EditText etBalance = view.findViewById(R.id.etAccBalance);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Add bank account")
                .setView(view)
                .setPositiveButton("Add", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String balStr = etBalance.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter an account name.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double balance = 0;
                    if (!balStr.isEmpty()) {
                        try {
                            balance = Double.parseDouble(balStr);
                        } catch (NumberFormatException ex) {
                            Toast.makeText(this, "Enter a valid starting balance.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    db.insertAccount(name, balance);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditAccountDialog(Account a) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_account, null);
        EditText etName = view.findViewById(R.id.etAccName);
        EditText etBalance = view.findViewById(R.id.etAccBalance);
        etName.setText(a.name);
        etBalance.setText(String.format(Locale.US, "%.2f", a.balance));
        etBalance.setHint("Balance (₹)");

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Edit account")
                .setView(view)
                .setPositiveButton("Save", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String balStr = etBalance.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter an account name.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double balance;
                    try {
                        balance = balStr.isEmpty() ? 0 : Double.parseDouble(balStr);
                    } catch (NumberFormatException ex) {
                        Toast.makeText(this, "Enter a valid balance.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    db.updateAccount(a.id, name, balance);
                    refresh();
                })
                .setNegativeButton("Cancel", null);

        // Only offer delete if removing this account wouldn't leave every payment/log flow
        // with nowhere to attach to unexpectedly - deleting the last account is still allowed
        // (that's how you get back to "no accounts set up"), just asked to confirm below.
        builder.setNeutralButton("Delete", (d, w) -> confirmDeleteAccount(a));
        builder.show();
    }

    private void confirmDeleteAccount(Account a) {
        String msg = a.isDefault
                ? "This is your default account. Another account (if any) will become the new default. This can't be undone."
                : "This can't be undone.";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete \"" + a.name + "\"?")
                .setMessage(msg)
                .setPositiveButton("Delete", (d, w) -> {
                    db.deleteAccount(a.id);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
