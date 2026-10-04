package com.expensetracker.upi;

/** One (name, phone number) pair from the device's phonebook, loaded in bulk by
 * MainActivity#loadContactsAsync() and filtered locally as the user types - see
 * MainActivity.ContactAdapter. Deliberately holds nothing UPI-related; that's looked up
 * separately per phone number via ContactStore once a suggestion is picked. */
final class ContactEntry {
    final String name;
    final String phone;

    ContactEntry(String name, String phone) {
        this.name = name;
        this.phone = phone;
    }

    @Override public String toString() {
        return name + "  \u2022  " + phone;
    }
}
