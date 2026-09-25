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
    // v4 adds the accounts table (bank accounts with an auto-tracked balance) and
    // expenses.account_id, linking each expense to the account it was paid from.
    private static final int DB_VERSION = 4;

    public ExpenseDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE expenses (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "merchant TEXT, upi_id TEXT, amount REAL, note TEXT," +
                "category TEXT, created_at INTEGER, status TEXT," +
                "txn_ref TEXT, response_code TEXT, req_tr TEXT, account_id INTEGER)");
        db.execSQL("CREATE TABLE accounts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT, balance REAL, is_default INTEGER DEFAULT 0)");
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
            db.execSQL("CREATE TABLE accounts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT, balance REAL, is_default INTEGER DEFAULT 0)");
        }
    }

    private static boolean isConfirmedStatus(String status) {
        return "SUCCESS".equals(status) || "LOGGED".equals(status);
    }

    /** Inserts a new expense tied to accountId (nullable - old behavior if null/no accounts
     * set up yet). If the row is already confirmed at insert time (SUCCESS/LOGGED, i.e. the
     * "already paid elsewhere" flows), the account is debited immediately. Rows inserted as
     * INITIATED/PENDING are debited later, when updateStatus() confirms them - see there. */
    public long insert(String merchant, String upiId, double amount, String note,
                       String category, String status, Long accountId) {
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

    /** Same as the 7-arg insert() with no account attached (pre-accounts call sites / no
     * default account set up yet). */
    public long insert(String merchant, String upiId, double amount, String note,
                       String category, String status) {
        return insert(merchant, upiId, amount, note, category, status, null);
    }

    /** The unique reference we generated and sent out with this payment request. */
    public void setRequestTr(long id, String tr) {
        ContentValues v = new ContentValues();
        v.put("req_tr", tr);
        getWritableDatabase().update("expenses", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Edits the user-editable fields of an existing row (used by the edit-expense dialog in
     * HistoryActivity). Status/txn_ref/response_code/req_tr/account_id are untouched - those
     * reflect what actually happened with the payment and aren't something the user should be
     * able to fake by editing. If the amount changes on a confirmed expense, the caller
     * (HistoryActivity) is responsible for adjusting the linked account's balance via
     * adjustAccountBalance() to match - this method only writes the row itself. */
    public void update(long id, String merchant, String upiId, double amount, String note, String category) {
        ContentValues v = new ContentValues();
        v.put("merchant", merchant);
        v.put("upi_id", upiId);
        v.put("amount", amount);
        v.put("note", note);
        v.put("category", category);
        getWritableDatabase().update("expenses", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Deletes the row only - if it was confirmed and linked to an account, the caller
     * (HistoryActivity) credits the account back via adjustAccountBalance() before/after
     * calling this, since only it has the pre-delete Expense to know the amount/account. */
    public void delete(long id) {
        getWritableDatabase().delete("expenses", "id = ?", new String[]{String.valueOf(id)});
    }

    public Expense getExpenseById(long id) {
        Cursor c = getReadableDatabase().query("expenses", null, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null);
        Expense result = null;
        if (c.moveToFirst()) result = expenseFromCursor(c);
        c.close();
        return result;
    }

    /** Called once the UPI app returns a result (or we learn the user cancelled/backed out).
     * If this transition newly confirms the expense (wasn't SUCCESS/LOGGED before, is now),
     * and it's linked to an account, that account is debited here - exactly once, since a
     * row only ever transitions into a confirmed status a single time. */
    public void updateStatus(long id, String status, String txnRef, String responseCode) {
        Expense before = getExpenseById(id);

        ContentValues v = new ContentValues();
        v.put("status", status);
        if (txnRef != null) v.put("txn_ref", txnRef);
        if (responseCode != null) v.put("response_code", responseCode);
        getWritableDatabase().update("expenses", v, "id = ?", new String[]{String.valueOf(id)});

        if (before != null && !before.isConfirmed() && isConfirmedStatus(status) && before.accountId != null) {
            adjustAccountBalance(before.accountId, -before.amount);
        }
    }

    public Cursor all() {
        return getReadableDatabase().query("expenses", null, null, null, null, null,
                "created_at DESC");
    }

    /** Same rows as {@link #all()}, converted into {@link Expense} objects for the analysis screen's adapter. */
    public List<Expense> getAllExpenses() {
        List<Expense> result = new ArrayList<>();
        Cursor c = all();
        while (c.moveToNext()) result.add(expenseFromCursor(c));
        c.close();
        return result;
    }

    private Expense expenseFromCursor(Cursor c) {
        int accountIdCol = c.getColumnIndexOrThrow("account_id");
        Long accountId = c.isNull(accountIdCol) ? null : c.getLong(accountIdCol);
        return new Expense(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("merchant")),
                c.getString(c.getColumnIndexOrThrow("upi_id")),
                c.getDouble(c.getColumnIndexOrThrow("amount")),
                c.getString(c.getColumnIndexOrThrow("note")),
                c.getString(c.getColumnIndexOrThrow("category")),
                c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getString(c.getColumnIndexOrThrow("status")),
                accountId);
    }

    /**
     * Distinct payees (by UPI ID) from past expenses, most-recently-paid first, for the
     * home screen's "Pay again" quick-pick row. Read-only convenience query - it doesn't
     * change how a payment is built or sent; MainActivity just uses the result to prefill
     * the same merchant/upiId fields a QR scan or manual entry would set.
     */

    // ---- Accounts ----------------------------------------------------------------------

    /** Adds a new bank account. The very first account ever added automatically becomes the
     * default (there'd otherwise be no way to pick one, and every payment/log flow needs a
     * default to attach to). Later additions are not auto-selected as default - see
     * setDefaultAccount(). */
    public long insertAccount(String name, double balance) {
        boolean makeDefault = getAllAccounts().isEmpty();
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("balance", balance);
        v.put("is_default", makeDefault ? 1 : 0);
        return getWritableDatabase().insert("accounts", null, v);
    }

    /** Renames/re-balances an existing account directly (e.g. reconciling with your real bank
     * statement) - this overwrites balance rather than adjusting it; use adjustAccountBalance()
     * for a relative change. */
    public void updateAccount(long id, String name, double balance) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("balance", balance);
        getWritableDatabase().update("accounts", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Adds delta (positive or negative) to an account's balance - the mechanism behind the
     * automatic debit-on-payment/credit-on-delete tracking. Balance is intentionally allowed
     * to go negative (e.g. an overdraft, or simply logging more than you'd tallied) rather
     * than being clamped at zero. */
    public void adjustAccountBalance(long id, double delta) {
        getWritableDatabase().execSQL("UPDATE accounts SET balance = balance + ? WHERE id = ?",
                new Object[]{delta, id});
    }

    /** Clears is_default on every account, then sets it on this one - so there's always at
     * most one default, which every payment/log flow attaches new expenses to automatically. */
    public void setDefaultAccount(long id) {
        SQLiteDatabase wdb = getWritableDatabase();
        ContentValues clear = new ContentValues();
        clear.put("is_default", 0);
        wdb.update("accounts", clear, null, null);
        ContentValues set = new ContentValues();
        set.put("is_default", 1);
        wdb.update("accounts", set, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Deletes an account. If it was the default, the next remaining account (if any) becomes
     * the new default, so there's never a gap where payments silently stop tracking an account
     * without the user explicitly choosing "no account" (which this app doesn't offer as a
     * distinct state - having zero accounts is that state). Expenses already linked to the
     * deleted account keep their account_id as history; it just won't resolve to a live row. */
    public void deleteAccount(long id) {
        boolean wasDefault = false;
        for (Account a : getAllAccounts()) if (a.id == id) wasDefault = a.isDefault;
        getWritableDatabase().delete("accounts", "id = ?", new String[]{String.valueOf(id)});
        if (wasDefault) {
            List<Account> remaining = getAllAccounts();
            if (!remaining.isEmpty()) setDefaultAccount(remaining.get(0).id);
        }
    }

    public List<Account> getAllAccounts() {
        List<Account> result = new ArrayList<>();
        Cursor c = getReadableDatabase().query("accounts", null, null, null, null, null, "id ASC");
        while (c.moveToNext()) {
            result.add(new Account(
                    c.getLong(c.getColumnIndexOrThrow("id")),
                    c.getString(c.getColumnIndexOrThrow("name")),
                    c.getDouble(c.getColumnIndexOrThrow("balance")),
                    c.getInt(c.getColumnIndexOrThrow("is_default")) == 1));
        }
        c.close();
        return result;
    }

    /** @return the default account, or null if none exist yet (accounts are opt-in - the app
     * works without any set up, it just won't auto-track a balance). */
    public Account getDefaultAccount() {
        for (Account a : getAllAccounts()) if (a.isDefault) return a;
        return null;
    }
}
