package com.expensetracker.upi;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class ExpenseDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "expenses.db";
    // v2 added txn_ref / response_code (the PSP's returned reference after payment).
    // v3 adds req_tr: the unique reference *we* generate and send with every outgoing
    // intent-based payment request, per NPCI spec, kept separate from the PSP's own
    // returned reference so both are visible for reconciliation/debugging.
    // v4 adds the accounts table plus expenses.account_id: every confirmed expense (SUCCESS/
    // LOGGED) is tagged with the account that was the default at the moment it was recorded,
    // so balance adjustments (insert/status-change/edit/delete) always know which account to
    // touch, even after the user later changes which account is default. account_id is left
    // NULL on rows from before v4 (no account existed yet) and on any account subsequently
    // deleted - those rows keep their history but no longer participate in balance math.
    // v5 adds req_uri (the full upi://pay... link actually launched, exactly as built in
    // PaymentActivity#launchPayment()) and source_qr (the raw content of the QR that request
    // was built from, if any - empty for manual entry). Together with the existing req_tr/
    // txn_ref/response_code this is enough to answer "what did we actually send, and what did
    // the payee's app do with it?" for any past payment without needing adb/Logcat - see
    // HistoryActivity's "View request details" on the edit dialog.
    private static final int DB_VERSION = 5;

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_LOGGED = "LOGGED";

    public ExpenseDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE expenses (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "merchant TEXT, upi_id TEXT, amount REAL, note TEXT," +
                "category TEXT, created_at INTEGER, status TEXT," +
                "txn_ref TEXT, response_code TEXT, req_tr TEXT, account_id INTEGER," +
                "req_uri TEXT, source_qr TEXT)");
        createAccountsTable(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN txn_ref TEXT");
            db.execSQL("ALTER TABLE expenses ADD COLUMN response_code TEXT");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN req_tr TEXT");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN account_id INTEGER");
            createAccountsTable(db);
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN req_uri TEXT");
            db.execSQL("ALTER TABLE expenses ADD COLUMN source_qr TEXT");
        }
    }

    private void createAccountsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS accounts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT, balance REAL, is_default INTEGER DEFAULT 0)");
    }

    private static boolean isConfirmedStatus(String status) {
        return STATUS_SUCCESS.equals(status) || STATUS_LOGGED.equals(status);
    }

    // ------------------------------------------------------------------
    // Expenses
    // ------------------------------------------------------------------

    /** Every new expense is tagged with whichever account is currently default (may be none,
     * if no accounts have been set up yet) - see the class-level note on account_id. If the
     * status being inserted is already confirmed (LOGGED, for "already paid elsewhere"), the
     * account balance is debited immediately; STATUS_INITIATED rows are debited later, when
     * updateStatus() confirms them. */
    public long insert(String merchant, String upiId, double amount, String note,
                       String category, String status) {
        Long accountId = getDefaultAccountId();
        ContentValues v = new ContentValues();
        v.put("merchant", merchant);
        v.put("upi_id", upiId);
        v.put("amount", amount);
        v.put("note", note);
        v.put("category", category);
        v.put("created_at", System.currentTimeMillis());
        v.put("status", status);
        if (accountId != null) v.put("account_id", accountId); else v.putNull("account_id");
        long id = getWritableDatabase().insert("expenses", null, v);
        if (accountId != null && isConfirmedStatus(status)) {
            adjustAccountBalance(accountId, -amount);
        }
        return id;
    }

    /** Records exactly what this payment attempt sent out, for later troubleshooting from
     * the History screen without needing adb/Logcat: the reference we generated (tr), the
     * full upi://pay... link actually launched (uri), and the raw content of the source QR
     * it was built from, if any (sourceQr - null/empty for manual entry). Called once, right
     * before launching the UPI intent in PaymentActivity#launchPayment(); doesn't affect the
     * request itself, only what's kept on record about it. */
    public void logOutgoingRequest(long id, String tr, String uri, String sourceQr) {
        ContentValues v = new ContentValues();
        v.put("req_tr", tr);
        v.put("req_uri", uri);
        v.put("source_qr", sourceQr);
        getWritableDatabase().update("expenses", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Edits the user-editable fields of an existing row (used by the edit-expense dialog in
     * HistoryActivity). Status/txn_ref/response_code/req_tr/account_id are untouched - those
     * reflect what actually happened with the payment and which account it was billed against,
     * neither of which the user should be able to fake by editing. If the row is confirmed and
     * tagged to an account, the account balance is adjusted by the difference between the old
     * and new amount so it stays in sync. */
    public void update(long id, String merchant, String upiId, double amount, String note, String category) {
        Cursor c = getReadableDatabase().query("expenses",
                new String[]{"status", "amount", "account_id"}, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null);
        if (c.moveToFirst()) {
            String oldStatus = c.getString(0);
            double oldAmount = c.getDouble(1);
            Long accountId = c.isNull(2) ? null : c.getLong(2);
            if (accountId != null && isConfirmedStatus(oldStatus)) {
                adjustAccountBalance(accountId, oldAmount - amount);
            }
        }
        c.close();

        ContentValues v = new ContentValues();
        v.put("merchant", merchant);
        v.put("upi_id", upiId);
        v.put("amount", amount);
        v.put("note", note);
        v.put("category", category);
        getWritableDatabase().update("expenses", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Deleting a confirmed, account-tagged expense refunds its amount back to that account. */
    public void delete(long id) {
        Cursor c = getReadableDatabase().query("expenses",
                new String[]{"status", "amount", "account_id"}, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null);
        if (c.moveToFirst()) {
            String status = c.getString(0);
            double amount = c.getDouble(1);
            Long accountId = c.isNull(2) ? null : c.getLong(2);
            if (accountId != null && isConfirmedStatus(status)) {
                adjustAccountBalance(accountId, amount);
            }
        }
        c.close();
        getWritableDatabase().delete("expenses", "id = ?", new String[]{String.valueOf(id)});
    }

    /** Called once the UPI app returns a result (or we learn the user cancelled/backed out).
     * Debits the tagged account the moment a row newly becomes confirmed (INITIATED/PENDING ->
     * SUCCESS), and refunds it if a previously-confirmed row is ever moved back to a
     * non-confirmed status - covers both directions even though only one is exercised today. */
    public void updateStatus(long id, String status, String txnRef, String responseCode) {
        Cursor c = getReadableDatabase().query("expenses",
                new String[]{"status", "amount", "account_id"}, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null);
        if (c.moveToFirst()) {
            String oldStatus = c.getString(0);
            double amount = c.getDouble(1);
            Long accountId = c.isNull(2) ? null : c.getLong(2);
            boolean wasConfirmed = isConfirmedStatus(oldStatus);
            boolean nowConfirmed = isConfirmedStatus(status);
            if (accountId != null && !wasConfirmed && nowConfirmed) {
                adjustAccountBalance(accountId, -amount);
            } else if (accountId != null && wasConfirmed && !nowConfirmed) {
                adjustAccountBalance(accountId, amount);
            }
        }
        c.close();

        ContentValues v = new ContentValues();
        v.put("status", status);
        if (txnRef != null) v.put("txn_ref", txnRef);
        if (responseCode != null) v.put("response_code", responseCode);
        getWritableDatabase().update("expenses", v, "id = ?", new String[]{String.valueOf(id)});
    }

    public Cursor all() {
        return getReadableDatabase().query("expenses", null, null, null, null, null,
                "created_at DESC");
    }

    /** Same rows as {@link #all()}, converted into {@link Expense} objects for the analysis screen's adapter. */
    public java.util.List<Expense> getAllExpenses() {
        java.util.List<Expense> result = new java.util.ArrayList<>();
        Cursor c = all();
        while (c.moveToNext()) {
            result.add(new Expense(
                    c.getLong(c.getColumnIndexOrThrow("id")),
                    c.getString(c.getColumnIndexOrThrow("merchant")),
                    c.getString(c.getColumnIndexOrThrow("upi_id")),
                    c.getDouble(c.getColumnIndexOrThrow("amount")),
                    c.getString(c.getColumnIndexOrThrow("note")),
                    c.getString(c.getColumnIndexOrThrow("category")),
                    c.getLong(c.getColumnIndexOrThrow("created_at")),
                    c.getString(c.getColumnIndexOrThrow("status")),
                    c.getString(c.getColumnIndexOrThrow("req_tr")),
                    c.getString(c.getColumnIndexOrThrow("req_uri")),
                    c.getString(c.getColumnIndexOrThrow("source_qr")),
                    c.getString(c.getColumnIndexOrThrow("txn_ref")),
                    c.getString(c.getColumnIndexOrThrow("response_code"))));
        }
        c.close();
        return result;
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    private void adjustAccountBalance(long accountId, double delta) {
        // Raw SQL rather than read-modify-write ContentValues: keeps the adjustment atomic
        // and lets the balance go negative freely (overdraft/credit accounts are allowed by
        // design - no floor check here).
        getWritableDatabase().execSQL("UPDATE accounts SET balance = balance + ? WHERE id = ?",
                new Object[]{delta, accountId});
    }

    /** Adds a new account with the given starting balance. The very first account created is
     * automatically made the default (there's otherwise no way to have a default). */
    public long insertAccount(String name, double startingBalance) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("balance", startingBalance);
        v.put("is_default", 0);
        long id = getWritableDatabase().insert("accounts", null, v);
        if (getAllAccounts().size() == 1) setDefaultAccount(id);
        return id;
    }

    /** Renames an account and/or manually corrects its balance (a deliberate override,
     * separate from the automatic debit/credit that happens as expenses are logged). */
    public void updateAccount(long id, String name, double balance) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("balance", balance);
        getWritableDatabase().update("accounts", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Deletes an account. Expenses previously tagged to it keep their history but are
     * detached (account_id -> NULL) so they no longer affect any account's balance. If the
     * deleted account was the default, the next remaining account (if any) becomes default. */
    public void deleteAccount(long id) {
        boolean wasDefault = false;
        for (Account a : getAllAccounts()) {
            if (a.id == id) { wasDefault = a.isDefault; break; }
        }

        ContentValues detach = new ContentValues();
        detach.putNull("account_id");
        getWritableDatabase().update("expenses", detach, "account_id = ?", new String[]{String.valueOf(id)});

        getWritableDatabase().delete("accounts", "id = ?", new String[]{String.valueOf(id)});

        if (wasDefault) {
            List<Account> remaining = getAllAccounts();
            if (!remaining.isEmpty()) setDefaultAccount(remaining.get(0).id);
        }
    }

    /** Makes this the one account new expenses are automatically billed against; clears the
     * flag on every other account first so exactly one (or zero) is ever default. */
    public void setDefaultAccount(long id) {
        ContentValues clear = new ContentValues();
        clear.put("is_default", 0);
        getWritableDatabase().update("accounts", clear, null, null);
        ContentValues set = new ContentValues();
        set.put("is_default", 1);
        getWritableDatabase().update("accounts", set, "id = ?", new String[]{String.valueOf(id)});
    }

    public List<Account> getAllAccounts() {
        List<Account> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query("accounts", null, null, null, null, null, "id ASC");
        while (c.moveToNext()) {
            list.add(new Account(
                    c.getLong(c.getColumnIndexOrThrow("id")),
                    c.getString(c.getColumnIndexOrThrow("name")),
                    c.getDouble(c.getColumnIndexOrThrow("balance")),
                    c.getInt(c.getColumnIndexOrThrow("is_default")) == 1));
        }
        c.close();
        return list;
    }

    /** The account new expenses are auto-billed to, or null if none has been set up yet /
     * none is currently marked default. */
    public Account getDefaultAccount() {
        for (Account a : getAllAccounts()) {
            if (a.isDefault) return a;
        }
        return null;
    }

    private Long getDefaultAccountId() {
        Account a = getDefaultAccount();
        return a == null ? null : a.id;
    }
}
