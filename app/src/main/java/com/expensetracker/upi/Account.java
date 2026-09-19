package com.expensetracker.upi;

/** Read-only view of one row from the accounts table. */
final class Account {
    final long id;
    final String name;
    final double balance;
    final boolean isDefault;

    Account(long id, String name, double balance, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.balance = balance;
        this.isDefault = isDefault;
    }
}
