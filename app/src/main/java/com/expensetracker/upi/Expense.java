package com.expensetracker.upi;

/** Read-only view of one row from the expenses table, for display in the analysis screen. */
final class Expense {
    final long id;
    final String merchant;
    final String upiId;
    final double amount;
    final String note;
    final String category;
    final long createdAt;
    final String status;
    // What was actually sent for this payment attempt (null for LOGGED/manual rows that never
    // built a UPI intent) and what the source QR contained, if any - see ExpenseDbHelper
    // #logOutgoingRequest(). Surfaced read-only in HistoryActivity's "View request details".
    final String reqTr;
    final String reqUri;
    final String sourceQr;
    final String txnRef;
    final String responseCode;

    Expense(long id, String merchant, String upiId, double amount, String note,
            String category, long createdAt, String status, String reqTr, String reqUri,
            String sourceQr, String txnRef, String responseCode) {
        this.id = id;
        this.merchant = merchant;
        this.upiId = upiId;
        this.amount = amount;
        this.note = note;
        this.category = category;
        this.createdAt = createdAt;
        this.status = status;
        this.reqTr = reqTr;
        this.reqUri = reqUri;
        this.sourceQr = sourceQr;
        this.txnRef = txnRef;
        this.responseCode = responseCode;
    }

    /** True when there's anything to show in "View request details" - i.e. this row actually
     * went through the UPI-intent flow at least once, rather than being a manually-logged
     * ("already paid elsewhere") entry that never built a request. */
    boolean hasRequestDetails() {
        return !isEmpty(reqUri) || !isEmpty(sourceQr) || !isEmpty(reqTr)
                || !isEmpty(txnRef) || !isEmpty(responseCode);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Confirmed = counts toward spend totals/category analysis (paid via this app or logged as already paid). */
    boolean isConfirmed() {
        return "SUCCESS".equals(status) || "LOGGED".equals(status);
    }

    boolean isFailed() {
        return "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    boolean isPending() {
        return !isConfirmed() && !isFailed();
    }
}
