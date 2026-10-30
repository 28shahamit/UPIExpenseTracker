package com.expensetracker.upi;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.textfield.TextInputEditText;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import androidx.core.util.Consumer;
import android.content.res.ColorStateList;
import android.graphics.Color;
import java.util.Calendar;
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

    private static final String[] TYPES = {"BANK", "WALLET", "CASH"};
    private String filter = "ALL";
    private boolean hideBalances = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accounts);
        BottomNav.wire(this, R.id.navAccounts);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        db = new ExpenseDbHelper(this);
        llAccounts = findViewById(R.id.llAccounts);
        tvNoAccounts = findViewById(R.id.tvNoAccounts);
        findViewById(R.id.btnAddAccount).setOnClickListener(v -> pickType());
        findViewById(R.id.btnTransferAny).setOnClickListener(v -> startTransfer(null));
        findViewById(R.id.btnEye).setOnClickListener(v -> { hideBalances = !hideBalances; refresh(); });
        findViewById(R.id.btnBackup).setOnClickListener(v ->
            new MaterialAlertDialogBuilder(this)
                .setTitle("Backup & Restore")
                .setItems(new String[]{"Export backup", "Restore backup", "Manage categories"}, (d, which) -> {
                    if (which == 0) backupLauncher.launch("upi_expense_backup.db");
                    else if (which == 1) restoreLauncher.launch(new String[]{"*/*"});
                    else showManageCategoriesDialog();
                }).show());
        String[] keys = {"ALL", "BANK", "WALLET", "CASH"};
        int[] ids = {R.id.chipAll, R.id.chipBank, R.id.chipWallet, R.id.chipCash};
        for (int i = 0; i < 4; i++) {
            final String k = keys[i];
            findViewById(ids[i]).setOnClickListener(v -> { filter = k; refresh(); });
        }
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

    private String money(double d) {
        if (hideBalances) return "\u20B9 \u2022\u2022\u2022\u2022";
        Locale in = new Locale("en", "IN");
        return "\u20B9 " + (d == Math.rint(d) ? String.format(in, "%,.0f", d) : String.format(in, "%,.2f", d));
    }

    private static int typeColor(String t) {
        return Color.parseColor("WALLET".equals(t) ? "#7C3AED" : "CASH".equals(t) ? "#F59E0B" : "#1E7BF2");
    }

    private static String typeEmoji(String t) {
        return "WALLET".equals(t) ? "\uD83D\uDC5B" : "CASH".equals(t) ? "\uD83D\uDCB5" : "\uD83C\uDFE6";
    }

    private static String typeTitle(String t) {
        return "WALLET".equals(t) ? "Wallet / UPI Lite Accounts" : "CASH".equals(t) ? "Cash Accounts" : "Bank Accounts";
    }

    private static String typeAddLabel(String t) {
        return "WALLET".equals(t) ? "+ Add Wallet / UPI Lite" : "CASH".equals(t) ? "+ Add Cash Account" : "+ Add Bank Account";
    }

    private void styleChip(int id, String label, int count, boolean selected) {
        TextView c = findViewById(id);
        c.setText(label + " (" + count + ")");
        c.setBackgroundTintList(ColorStateList.valueOf(getColor(selected ? R.color.brand_primary : R.color.brand_primary_light)));
        c.setTextColor(getColor(selected ? R.color.white : R.color.on_surface_secondary));
    }

    /** Green for positive, red for negative, neutral for zero (or while balances are hidden). */
    private void tint(TextView tv, double v) {
        tv.setTextColor(getColor(hideBalances || v == 0 ? R.color.on_surface : v < 0 ? R.color.status_failed : R.color.status_success));
    }

    private void refresh() {
        llAccounts.removeAllViews();
        List<Account> accounts = db.getAllAccounts();
        tvNoAccounts.setVisibility(accounts.isEmpty() ? View.VISIBLE : View.GONE);

        double[] sums = new double[3];
        int[] counts = new int[3];
        for (Account a : accounts) {
            int i = "WALLET".equals(a.type) ? 1 : "CASH".equals(a.type) ? 2 : 0;
            sums[i] += a.balance;
            counts[i]++;
        }
        double total = sums[0] + sums[1] + sums[2];
        ((TextView) findViewById(R.id.tvTotal)).setText(money(total));
        ((TextView) findViewById(R.id.tvSumBank)).setText(money(sums[0]));
        ((TextView) findViewById(R.id.tvSumWallet)).setText(money(sums[1]));
        ((TextView) findViewById(R.id.tvSumCash)).setText(money(sums[2]));
        ((TextView) findViewById(R.id.tileBank)).setText(money(sums[0]));
        ((TextView) findViewById(R.id.tileWallet)).setText(money(sums[1]));
        ((TextView) findViewById(R.id.tileCash)).setText(money(sums[2]));
        tint(findViewById(R.id.tvTotal), total);
        tint(findViewById(R.id.tileBank), sums[0]);
        tint(findViewById(R.id.tileWallet), sums[1]);
        tint(findViewById(R.id.tileCash), sums[2]);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        double change = db.sumDeltaSince(cal.getTimeInMillis());
        TextView tvTrend = findViewById(R.id.tvTrend);
        boolean up = change >= 0;
        tvTrend.setText(hideBalances ? "" : (up ? "\u25B2 +" : "\u25BC -") + money(Math.abs(change)) + " from last month");
        tvTrend.setTextColor(getColor(up ? R.color.status_success : R.color.status_failed));

        styleChip(R.id.chipAll, "All", accounts.size(), "ALL".equals(filter));
        styleChip(R.id.chipBank, "Bank", counts[0], "BANK".equals(filter));
        styleChip(R.id.chipWallet, "Wallet/Lite", counts[1], "WALLET".equals(filter));
        styleChip(R.id.chipCash, "Cash", counts[2], "CASH".equals(filter));

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int t = 0; t < 3; t++) {
            String type = TYPES[t];
            if (!"ALL".equals(filter) && !filter.equals(type)) continue;
            View sec = inflater.inflate(R.layout.item_account_section, llAccounts, false);
            ((TextView) sec.findViewById(R.id.tvSecIcon)).setText(typeEmoji(type));
            ((TextView) sec.findViewById(R.id.tvSecTitle)).setText(typeTitle(type) + " (" + counts[t] + ")");
            TextView add = sec.findViewById(R.id.tvSecAdd);
            add.setText(typeAddLabel(type));
            add.setOnClickListener(v -> showAddAccountDialog(type));
            LinearLayout rows = sec.findViewById(R.id.llSecRows);
            if (counts[t] == 0) sec.findViewById(R.id.cardSecRows).setVisibility(View.GONE);
            boolean first = true;
            for (Account a : accounts) {
                if (!a.type.equals(type)) continue;
                if (!first) {
                    View div = new View(this);
                    div.setBackgroundColor(getColor(R.color.divider));
                    rows.addView(div, new LinearLayout.LayoutParams(-1, 1));
                }
                first = false;
                rows.addView(buildRow(inflater, rows, a));
            }
            llAccounts.addView(sec);
        }
    }

    private View buildRow(LayoutInflater inflater, LinearLayout parent, Account a) {
        View row = inflater.inflate(R.layout.item_account, parent, false);
        TextView icon = row.findViewById(R.id.tvAccIcon);
        icon.setText(typeEmoji(a.type));
        icon.setBackgroundTintList(ColorStateList.valueOf(typeColor(a.type)));
        ((TextView) row.findViewById(R.id.tvAccName)).setText(a.name);
        row.findViewById(R.id.tvAccDefaultChip).setVisibility(a.isDefault ? View.VISIBLE : View.GONE);
        String sub = "WALLET".equals(a.type) ? "UPI Lite / Wallet"
                : "CASH".equals(a.type) ? "Physical cash in hand"
                : a.last4.isEmpty() ? "Bank account" : "Account \u2022\u2022\u2022\u2022\u2022 " + a.last4;
        ((TextView) row.findViewById(R.id.tvAccSub)).setText(sub);
        TextView upi = row.findViewById(R.id.tvAccUpi);
        upi.setVisibility(a.upiIds.isEmpty() ? View.GONE : View.VISIBLE);
        upi.setText("UPI: " + a.upiIds);
        ((TextView) row.findViewById(R.id.tvAccBalance)).setText(money(a.balance));
        tint(row.findViewById(R.id.tvAccBalance), a.balance);

        row.findViewById(R.id.rowTop).setOnClickListener(v -> openBalanceHistory(a, false));
        row.findViewById(R.id.btnStatement).setOnClickListener(v -> openBalanceHistory(a, false));
        row.findViewById(R.id.btnAdjust).setOnClickListener(v -> openBalanceHistory(a, true));
        row.findViewById(R.id.btnTransfer).setOnClickListener(v -> startTransfer(a));
        View setDef = row.findViewById(R.id.btnSetDefault);
        setDef.setVisibility(a.isDefault ? View.GONE : View.VISIBLE);
        setDef.setOnClickListener(v -> {
            db.setDefaultAccount(a.id);
            Toast.makeText(this, a.name + " is now your default account.", Toast.LENGTH_SHORT).show();
            refresh();
        });
        row.findViewById(R.id.btnAccMenu).setOnClickListener(v -> showEditAccountDialog(a));
        // Mockup: actions live behind the kebab menu instead of a button strip on every row.
        row.findViewById(R.id.rowActions).setVisibility(View.GONE);
        row.findViewById(R.id.tvAccChevron).setOnClickListener(v -> showAccountMenu(a));
        return row;
    }

    private void openBalanceHistory(Account a, boolean openChangeDialog) {
        Intent i = new Intent(this, BalanceHistoryActivity.class);
        i.putExtra("accountId", a.id);
        i.putExtra("accountName", a.name);
        i.putExtra("openChange", openChangeDialog);
        startActivity(i);
    }

    private void pickType() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_add_account, null);
        v.findViewById(R.id.optBank).setOnClickListener(x -> { sheet.dismiss(); showAddAccountDialog("BANK"); });
        v.findViewById(R.id.optWallet).setOnClickListener(x -> { sheet.dismiss(); showAddAccountDialog("WALLET"); });
        v.findViewById(R.id.optCash).setOnClickListener(x -> { sheet.dismiss(); showAddAccountDialog("CASH"); });
        sheet.setContentView(v);
        sheet.show();
    }

    private void showAddAccountDialog(String type) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_account, null);
        EditText etName = view.findViewById(R.id.etAccName);
        EditText etLast4 = view.findViewById(R.id.etAccLast4);
        EditText etUpi = view.findViewById(R.id.etAccUpi);
        EditText etBalance = view.findViewById(R.id.etAccBalance);
        com.google.android.material.materialswitch.MaterialSwitch swDefault = view.findViewById(R.id.swDefault);
        swDefault.setVisibility(View.VISIBLE);
        boolean bank = "BANK".equals(type), cash = "CASH".equals(type);
        ((TextView) view.findViewById(R.id.tvDlgTitle)).setText(bank ? "Add Bank Account" : cash ? "Add Cash Account" : "Add Wallet / UPI Lite");
        etName.setHint(bank ? "e.g. My SBI Account" : cash ? "e.g. Cash Wallet" : "e.g. PhonePe Wallet");
        if (!bank) view.findViewById(R.id.tilLast4).setVisibility(View.GONE);
        if (cash) view.findViewById(R.id.tilUpi).setVisibility(View.GONE);
        ((Button) view.findViewById(R.id.btnDlgOk)).setText("Add Account");
        AlertDialog dlg = new MaterialAlertDialogBuilder(this).setView(view).create();
        view.findViewById(R.id.btnDlgCancel).setOnClickListener(v -> dlg.dismiss());
        view.findViewById(R.id.btnDlgClose).setOnClickListener(v -> dlg.dismiss());
        view.findViewById(R.id.btnDlgOk).setOnClickListener(v -> {
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
            long newId = db.insertAccount(name, balance, type, etLast4.getText().toString().trim(), etUpi.getText().toString().trim());
            if (swDefault.isChecked() && newId > 0) db.setDefaultAccount(newId);
            dlg.dismiss();
            refresh();
        });
        dlg.show();
    }

    private void showAccountMenu(Account a) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p, p, p);
        TextView head = new TextView(this);
        head.setText(a.name);
        head.setTextSize(18);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setTextColor(getColor(R.color.on_surface));
        head.setPadding(p / 2, 0, 0, p / 2);
        box.addView(head);
        menuRow(box, sheet, "\u270F\uFE0F   Edit account", false, () -> showEditAccountDialog(a));
        if (!a.isDefault) menuRow(box, sheet, "\u2B50   Set as default", false, () -> {
            db.setDefaultAccount(a.id);
            Toast.makeText(this, a.name + " is now your default account.", Toast.LENGTH_SHORT).show();
            refresh();
        });
        menuRow(box, sheet, "\uD83D\uDCB1   Change balance", false, () -> openBalanceHistory(a, true));
        menuRow(box, sheet, "\uD83D\uDCC4   View statement", false, () -> openBalanceHistory(a, false));
        menuRow(box, sheet, "\uD83D\uDDD1\uFE0F   Delete account", true, () -> confirmDeleteAccount(a));
        sheet.setContentView(box);
        sheet.show();
    }

    private void menuRow(LinearLayout box, com.google.android.material.bottomsheet.BottomSheetDialog sheet,
                         String label, boolean danger, Runnable action) {
        TextView r = new TextView(this);
        r.setText(label);
        r.setTextSize(16);
        r.setTextColor(getColor(danger ? R.color.status_failed : R.color.on_surface));
        int p = (int) (14 * getResources().getDisplayMetrics().density);
        r.setPadding(p, p, p, p);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        r.setBackgroundResource(tv.resourceId);
        r.setOnClickListener(x -> { sheet.dismiss(); action.run(); });
        box.addView(r);
    }

    private void showEditAccountDialog(Account a) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_account, null);
        EditText etName = view.findViewById(R.id.etAccName);
        EditText etLast4 = view.findViewById(R.id.etAccLast4);
        EditText etUpi = view.findViewById(R.id.etAccUpi);
        com.google.android.material.materialswitch.MaterialSwitch swDefault = view.findViewById(R.id.swDefault);
        etName.setText(a.name);
        etLast4.setText(a.last4);
        etUpi.setText(a.upiIds);
        ((TextView) view.findViewById(R.id.tvDlgTitle)).setText("BANK".equals(a.type) ? "Edit Bank Account" : "CASH".equals(a.type) ? "Edit Cash Account" : "Edit Wallet / UPI Lite");
        // Balance changes go through "Adjust" so each one lands in the ledger with a reason.
        view.findViewById(R.id.tilBal).setVisibility(View.GONE);
        if (!"BANK".equals(a.type)) view.findViewById(R.id.tilLast4).setVisibility(View.GONE);
        if ("CASH".equals(a.type)) view.findViewById(R.id.tilUpi).setVisibility(View.GONE);
        swDefault.setVisibility(View.VISIBLE);
        swDefault.setChecked(a.isDefault);
        swDefault.setEnabled(!a.isDefault);
        ((Button) view.findViewById(R.id.btnDlgOk)).setText("Save Changes");
        AlertDialog dlg = new MaterialAlertDialogBuilder(this).setView(view).create();
        view.findViewById(R.id.btnDlgCancel).setOnClickListener(v -> dlg.dismiss());
        view.findViewById(R.id.btnDlgClose).setOnClickListener(v -> dlg.dismiss());
        view.findViewById(R.id.btnDlgOk).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Enter an account name.", Toast.LENGTH_SHORT).show();
                return;
            }
            db.updateAccountDetails(a.id, name, etLast4.getText().toString().trim(), etUpi.getText().toString().trim());
            if (swDefault.isChecked() && !a.isDefault) db.setDefaultAccount(a.id);
            dlg.dismiss();
            refresh();
        });
        dlg.show();
    }

    private void pickAccount(String title, long excludeId, Consumer<Account> done) {
        List<Account> opts = new java.util.ArrayList<>();
        for (Account x : db.getAllAccounts()) if (x.id != excludeId) opts.add(x);
        String[] labels = new String[opts.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = opts.get(i).name + "  (" + money(opts.get(i).balance) + ")";
        new MaterialAlertDialogBuilder(this).setTitle(title)
                .setItems(labels, (d, which) -> done.accept(opts.get(which)))
                .setNegativeButton("Cancel", null).show();
    }

    /** Transfer Money: from/to pickers, amount with quick-add chips, then a success dialog. */
    private void startTransfer(Account from) {
        List<Account> all = db.getAllAccounts();
        if (all.size() < 2) {
            Toast.makeText(this, "Add at least two accounts to transfer between them.", Toast.LENGTH_LONG).show();
            return;
        }
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_transfer, null);
        android.widget.Spinner spFrom = v.findViewById(R.id.spFrom);
        android.widget.Spinner spTo = v.findViewById(R.id.spTo);
        String[] labels = new String[all.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = all.get(i).name + "  \u2022  Balance: " + money(all.get(i).balance);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        spFrom.setAdapter(ad);
        spTo.setAdapter(ad);
        int fi = 0;
        if (from != null) for (int i = 0; i < all.size(); i++) if (all.get(i).id == from.id) fi = i;
        spFrom.setSelection(fi);
        spTo.setSelection(fi == 0 ? 1 : 0);
        EditText et = v.findViewById(R.id.etTransferAmt);
        int[] ids = {R.id.q100, R.id.q500, R.id.q1000, R.id.q5000};
        int[] adds = {100, 500, 1000, 5000};
        for (int k = 0; k < ids.length; k++) {
            final int add = adds[k];
            v.findViewById(ids[k]).setOnClickListener(x -> {
                double cur = 0;
                try { cur = Double.parseDouble(et.getText().toString().trim()); } catch (NumberFormatException ignored) { }
                et.setText(String.format(Locale.US, "%.2f", cur + add));
                et.setSelection(et.getText().length());
            });
        }
        AlertDialog dlg = new MaterialAlertDialogBuilder(this).setTitle("Transfer Money").setView(v)
                .setPositiveButton("Transfer", null).setNegativeButton("Cancel", null).show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            Account f = all.get(spFrom.getSelectedItemPosition());
            Account to = all.get(spTo.getSelectedItemPosition());
            double amt;
            try { amt = Double.parseDouble(et.getText().toString().trim()); } catch (NumberFormatException ex) { amt = 0; }
            if (f.id == to.id) { Toast.makeText(this, "Choose two different accounts.", Toast.LENGTH_SHORT).show(); return; }
            if (!(amt > 0)) { Toast.makeText(this, "Enter a valid amount.", Toast.LENGTH_SHORT).show(); return; }
            db.transfer(f.id, to.id, amt, f.name, to.name);
            dlg.dismiss();
            refresh();
            showTransferSuccess(f, to, amt);
        });
    }

    private void showTransferSuccess(Account f, Account to, double amt) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_transfer_success, null);
        ((TextView) v.findViewById(R.id.tvTsAmount)).setText(money(amt));
        ((TextView) v.findViewById(R.id.tvTsDesc)).setText("Transferred from " + f.name + "\nto " + to.name);
        new MaterialAlertDialogBuilder(this).setView(v)
                .setPositiveButton("Done", null)
                .setNeutralButton("View statement", (d, w) -> openBalanceHistory(f, false))
                .show();
    }

    private void confirmDeleteAccount(Account a) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_delete_account, null);
        ((TextView) v.findViewById(R.id.tvDelMsg)).setText("This will permanently delete \"" + a.name + "\" and its transactions."
                + (a.isDefault ? "\nAnother account (if any) becomes the default." : "") + "\nThis action cannot be undone.");
        AlertDialog dlg = new MaterialAlertDialogBuilder(this).setView(v)
                .setPositiveButton("Delete", (d, w) -> { db.deleteAccount(a.id); refresh(); })
                .setNegativeButton("Cancel", null).show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.status_failed));
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Manage categories").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) { showManageCategoriesDialog(); return true; }
        return super.onOptionsItemSelected(item);
    }

    /** Settings-style entry point for renaming/deleting any category, default or custom -
     * see CategoryStore for how renaming/deleting a built-in default is handled under the
     * hood. Renaming/deleting only affects future category pickers; past expenses keep
     * whatever category text they were saved with. */
    private void showManageCategoriesDialog() {
        List<String> all = CategoryStore.allCategories(this);
        if (all.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Manage categories")
                    .setMessage("No categories left - add one from the category picker on the Pay screen.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] names = all.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Manage categories")
                .setItems(names, (d, which) -> showCategoryActionDialog(names[which]))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showCategoryActionDialog(String name) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(name)
                .setItems(new String[]{"Rename", "Delete"}, (d, which) -> {
                    if (which == 0) showRenameCategoryDialog(name);
                    else new MaterialAlertDialogBuilder(this)
                            .setTitle("Delete \"" + name + "\"?")
                            .setMessage("This only removes it from future category pickers.")
                            .setPositiveButton("Delete", (d2, w2) -> {
                                CategoryStore.removeCategory(this, name);
                                Toast.makeText(this, "Deleted \"" + name + "\".", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Cancel", null).show();
                }).show();
    }

    private void showRenameCategoryDialog(String oldName) {
        TextInputEditText input = new TextInputEditText(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        input.setText(oldName);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Rename category")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (!CategoryStore.renameCategory(this, oldName, newName)) {
                        Toast.makeText(this, "Couldn't rename - name is blank or already used.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, "Renamed to \"" + newName + "\".", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }
}
