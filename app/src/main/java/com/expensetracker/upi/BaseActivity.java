package com.expensetracker.upi;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

/**
 * Common base for every screen in the app. Its only job is display/layout, not
 * business logic - nothing here touches payments, accounts, or the database.
 *
 * On API 35 (Android 15) and above the system enforces edge-to-edge drawing by
 * default once an app targets SDK 35, which this app does. That means, unless an
 * activity opts out, its content can be laid out underneath the status bar and
 * navigation bar rather than below/above them - which is what made the system
 * clock/battery/notification icons visually merge with this app's own top content
 * on newer devices, instead of sitting on their own clearly visible bar.
 *
 * Calling setDecorFitsSystemWindows(true) restores the pre-15 behavior: the system
 * reserves real space for the status/navigation bars and paints them as solid bars
 * in the colors set in styles.xml, and the window content is inset to sit below/
 * above them automatically. WindowCompat makes this a no-op on older API levels,
 * so it's safe across this app's full minSdk 23+ range.
 */
public abstract class BaseActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
    }
}
