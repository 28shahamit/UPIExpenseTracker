package com.expensetracker.upi;

import android.app.Activity;
import android.content.Intent;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/** Wires the bottom nav bar present on MainActivity/HistoryActivity/AccountsActivity so the
 *  three screens behave like tabs of one app instead of a stack of unrelated activities. */
final class BottomNav {
    private BottomNav() {}

    static void wire(Activity activity, int selectedId) {
        BottomNavigationView nav = activity.findViewById(R.id.bottomNav);
        if (nav == null) return;
        nav.setSelectedItemId(selectedId);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == selectedId) return true; // already on this tab
            Class<?> target = id == R.id.navDashboard ? HistoryActivity.class
                    : id == R.id.navAccounts ? AccountsActivity.class
                    : MainActivity.class;
            activity.startActivity(new Intent(activity, target));
            activity.finish();
            return true;
        });
    }
}
