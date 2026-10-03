package com.expensetracker.upi;

/** One row of an account's balance history - see ExpenseDbHelper#adjustAccountBalance, the
 * only place that writes these. Append-only: a correction to a past change is always a new
 * entry, never an edit to an old one, so this is a permanent, honest trail of how an
 * account's balance got to where it is. */
final class LedgerEntry {
    final long id;
    final long accountId;
    final long createdAt;
    final double delta;
    final double balanceAfter;
    final String reason;
    final Long expenseId; // null for manual corrections/opening balance - nothing to link to

    LedgerEntry(long id, long accountId, long createdAt, double delta, double balanceAfter,
                String reason, Long expenseId) {
        this.id = id;
        this.accountId = accountId;
        this.createdAt = createdAt;
        this.delta = delta;
        this.balanceAfter = balanceAfter;
        this.reason = reason;
        this.expenseId = expenseId;
    }
}
