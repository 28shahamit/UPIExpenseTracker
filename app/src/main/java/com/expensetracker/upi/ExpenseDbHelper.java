package com.expensetracker.upi;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class ExpenseDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "expenses.db";
    // v2 added txn_ref / response_code (the PSP's returned reference after payment).
    // v3 adds req_tr: the unique reference *we* generate and send with every outgoing
    // intent-based payment request, per NPCI spec, kept separate from the PSP's own
    // returned reference so both are visible for reconciliation/debugging.
    private static final int DB_VERSION = 3;

    public ExpenseDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE expenses (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "merchant TEXT, upi_id TEXT, amount REAL, note TEXT," +
                "category TEXT, created_at INTEGER, status TEXT," +
                "txn_ref TEXT, response_code TEXT, req_tr TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN txn_ref TEXT");
            db.execSQL("ALTER TABLE expenses ADD COLUMN response_code TEXT");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN req_tr TEXT");
        }
    }

    public long insert(String merchant, String upiId, double amount, String note,
                       String category, String status) {
        ContentValues v = new ContentValues();
        v.put("merchant", merchant);
        v.put("upi_id", upiId);
        v.put("amount", amount);
        v.put("note", note);
        v.put("category", category);
        v.put("created_at", System.currentTimeMillis());
        v.put("status", status);
        return getWritableDatabase().insert("expenses", null, v);
    }

    /** The unique reference we generated and sent out with this payment request. */
    public void setRequestTr(long id, String tr) {
        ContentValues v = new ContentValues();
        v.put("req_tr", tr);
        getWritableDatabase().update("expenses", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Called once the UPI app returns a result (or we learn the user cancelled/backed out). */
    public void updateStatus(long id, String status, String txnRef, String responseCode) {
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
}
