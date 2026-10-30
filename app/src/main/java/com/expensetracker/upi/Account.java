package com.expensetracker.upi;

/** Read-only view of one row from the accounts table. Balance is allowed to go negative -
 * it's a running tally the app maintains automatically, not a hard limit. type is BANK,
 * WALLET (wallet / UPI Lite) or CASH; last4 and upiIds are optional display details. */
final class Account {
    final long id;
    final String name;
    final double balance;
    final boolean isDefault;
    final String type;
    final String last4;
    final String upiIds;

    Account(long id, String name, double balance, boolean isDefault, String type, String last4, String upiIds) {
        this.id = id;
        this.name = name;
        this.balance = balance;
        this.isDefault = isDefault;
        this.type = type == null ? "BANK" : type;
        this.last4 = last4 == null ? "" : last4;
        this.upiIds = upiIds == null ? "" : upiIds;
    }
}
