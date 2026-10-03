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
    final Long accountId; // null for rows created before accounts existed, or logged with none set

    Expense(long id, String merchant, String upiId, double amount, String note,
            String category, long createdAt, String status, Long accountId) {
        this.id = id;
        this.merchant = merchant;
        this.upiId = upiId;
        this.amount = amount;
        this.note = note;
        this.category = category;
        this.createdAt = createdAt;
        this.status = status;
        this.accountId = accountId;
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
