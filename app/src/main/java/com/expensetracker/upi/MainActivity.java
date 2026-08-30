package com.expensetracker.upi;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final String STATUS_INITIATED = "INITIATED";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_PENDING = "PENDING"; // opened a UPI app, outcome unclear
    private static final String STATUS_LOGGED = "LOGGED"; // paid outside this app (e.g. directly in GPay), just recorded

    private TextView tvMerchant, tvStatus;
    private EditText etAmount, etNote;
    private Spinner spCategory;
    private String upiId = "", merchant = "", rawUpi = "";
    private ExpenseDbHelper db;

    // Tracks the expense row waiting on the user to confirm what happened after they
    // switched to their UPI app. -1 = none pending. Saved/restored across process death
    // so we don't "lose" a payment if Android kills this activity while that app is open.
    private long pendingExpenseId = -1;
    private boolean awaitingManualConfirmation = false;
    // What to show in the "did this go through?" prompt when the user comes back.
    private String pendingSummary = "";

    // Uses the system picker (Storage Access Framework) - no READ_MEDIA_IMAGES /
    // READ_EXTERNAL_STORAGE permission required.
    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                String contents = decodeQrFromUri(uri);
                if (contents != null) {
                    handleQr(contents);
                } else {
                    tvStatus.setText("Couldn't find a readable QR code in that photo. Try a clearer image.");
                }
            });

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startScanner();
                else Toast.makeText(this, "Camera permission is required to scan a QR.", Toast.LENGTH_LONG).show();
            });

    // No pre-query for installed UPI apps here on purpose - that detection step is what
    // broke repeatedly across Android versions/OEMs in earlier attempts. This just fires
    // the intent and lets Android resolve it natively (its own chooser if >1 app can
    // handle it); ActivityNotFoundException in launchPayment() is the only "none installed"
    // signal we rely on. The result callback itself isn't parsed - UPI apps' response
    // payloads proved unreliable to parse consistently, so confirmation is manual (see
    // onResume()/showPaymentConfirmationDialog()) regardless of what comes back here.
    private final ActivityResultLauncher<Intent> upiPaymentLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> { });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new ExpenseDbHelper(this);

        if (savedInstanceState != null) {
            pendingExpenseId = savedInstanceState.getLong("pendingExpenseId", -1);
            awaitingManualConfirmation = savedInstanceState.getBoolean("awaitingManualConfirmation", false);
            pendingSummary = savedInstanceState.getString("pendingSummary", "");
            upiId = savedInstanceState.getString("upiId", "");
            merchant = savedInstanceState.getString("merchant", "");
        }

        tvMerchant = findViewById(R.id.tvMerchant);
        tvStatus = findViewById(R.id.tvStatus);
        etAmount = findViewById(R.id.etAmount);
        etNote = findViewById(R.id.etNote);
        spCategory = findViewById(R.id.spCategory);

        String[] categories = {"Food", "Groceries", "Transport", "Bills", "Shopping",
                "Entertainment", "Health", "Education", "Travel", "Other"};
        spCategory.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, categories));

        findViewById(R.id.btnScan).setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                startScanner();
            else cameraPermission.launch(Manifest.permission.CAMERA);
        });
        findViewById(R.id.btnGallery).setOnClickListener(v -> galleryLauncher.launch("image/*"));
        findViewById(R.id.btnManual).setOnClickListener(v -> showManualEntryDialog());
        findViewById(R.id.btnPay).setOnClickListener(v -> launchPayment());
        findViewById(R.id.btnLogOnly).setOnClickListener(v -> logOnly());
        findViewById(R.id.btnHistory).setOnClickListener(v -> showHistory());
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("pendingExpenseId", pendingExpenseId);
        outState.putBoolean("awaitingManualConfirmation", awaitingManualConfirmation);
        outState.putString("pendingSummary", pendingSummary);
        outState.putString("upiId", upiId);
        outState.putString("merchant", merchant);
    }

    @Override protected void onResume() {
        super.onResume();
        if (awaitingManualConfirmation && pendingExpenseId != -1) {
            // Clear the flag immediately so backgrounding/resuming again (e.g. the system
            // dims the screen, or the user checks another app) doesn't reshow this.
            awaitingManualConfirmation = false;
            showPaymentConfirmationDialog(pendingExpenseId, pendingSummary);
        }
    }

    private void showPaymentConfirmationDialog(long id, String summary) {
        new AlertDialog.Builder(this)
                .setTitle("Did this payment go through?")
                .setMessage(summary)
                .setCancelable(false)
                .setPositiveButton("Paid successfully", (d, w) -> {
                    db.updateStatus(id, STATUS_SUCCESS, null, null);
                    pendingExpenseId = -1;
                    tvStatus.setText("Expense #" + id + " confirmed as paid.");
                })
                .setNegativeButton("Failed / didn't pay", (d, w) -> {
                    db.updateStatus(id, STATUS_FAILED, null, null);
                    pendingExpenseId = -1;
                    tvStatus.setText("Expense #" + id + " marked failed and excluded from totals.");
                })
                .setNeutralButton("Not sure yet", (d, w) ->
                        tvStatus.setText("Expense #" + id + " left pending - check history to update it once you know."))
                .show();
    }

    private void startScanner() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Scan the merchant's UPI QR");
        integrator.setBeepEnabled(true);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) handleQr(result.getContents());
            else tvStatus.setText("QR scan cancelled.");
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleQr(String contents) {
        rawUpi = contents.trim();
        try {
            Uri u = Uri.parse(rawUpi);
            if (!"upi".equalsIgnoreCase(u.getScheme()) || !"pay".equalsIgnoreCase(u.getHost())) {
                throw new IllegalArgumentException();
            }
            upiId = u.getQueryParameter("pa");
            merchant = u.getQueryParameter("pn");
            if (upiId == null || upiId.isEmpty()) throw new IllegalArgumentException();
            if (merchant == null || merchant.isEmpty()) merchant = upiId;
            tvMerchant.setText("Merchant: " + merchant + "\nUPI ID: " + upiId);
            String presetAmount = u.getQueryParameter("am");
            if (presetAmount != null && !presetAmount.isEmpty() && etAmount.getText().length() == 0)
                etAmount.setText(presetAmount);
            tvStatus.setText("QR scanned. Add your note and category, then pay.");
        } catch (Exception e) {
            upiId = "";
            merchant = "";
            rawUpi = "";
            tvMerchant.setText("Invalid or unsupported UPI QR");
            tvStatus.setText("Please scan a standard UPI payment QR.");
        }
    }

    /** Decodes a QR code from an arbitrary gallery image using the same ZXing engine as live scanning. */
    private String decodeQrFromUri(Uri uri) {
        try {
            Bitmap bitmap = loadBitmap(uri);
            if (bitmap == null) return null;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new MultiFormatReader().decode(binaryBitmap);
            return result.getText();
        } catch (Exception e) {
            return null; // no QR found / unreadable image - handled by the caller
        }
    }

    private Bitmap loadBitmap(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            // Force a software bitmap: getPixels() below can't read a hardware bitmap.
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        } else {
            return MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
        }
    }

    /**
     * Lets the user pay a UPI ID (VPA) directly, or a phone number plus a chosen PSP handle.
     * There is no universal "pay by mobile number" deep link - each UPI app resolves phone
     * numbers to VPAs internally using data we don't have access to - so a phone number
     * entered here is only a best-effort VPA guess. The UPI app's own payee-name display
     * before approval is the real safety check; we warn the user to rely on that.
     */
    private void showManualEntryDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_manual_entry, null);
        EditText etPayeeInput = view.findViewById(R.id.etPayeeInput);
        EditText etPayeeName = view.findViewById(R.id.etPayeeName);
        TextView tvHandleLabel = view.findViewById(R.id.tvHandleLabel);
        Spinner spHandle = view.findViewById(R.id.spHandle);

        String[] handleLabels = {"@upi", "@ybl (PhonePe)", "@paytm (Paytm)", "@oksbi (SBI)",
                "@okhdfcbank (HDFC)", "@okicici (ICICI)", "@okaxis (Axis)", "@ibl (IDFC/PhonePe)"};
        String[] handleValues = {"@upi", "@ybl", "@paytm", "@oksbi", "@okhdfcbank", "@okicici", "@okaxis", "@ibl"};
        spHandle.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, handleLabels));

        etPayeeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                boolean isBareMobile = s.toString().trim().matches("^[6-9]\\d{9}$");
                tvHandleLabel.setVisibility(isBareMobile ? View.VISIBLE : View.GONE);
                spHandle.setVisibility(isBareMobile ? View.VISIBLE : View.GONE);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pay by UPI ID or phone")
                .setView(view)
                .setPositiveButton("Continue", null) // set below so invalid input doesn't auto-dismiss
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String input = etPayeeInput.getText().toString().trim();
            String resolvedVpa;

            if (input.matches("^[\\w.+-]{2,256}@[A-Za-z]{2,64}$")) {
                resolvedVpa = input;
            } else if (input.matches("^[6-9]\\d{9}$")) {
                resolvedVpa = input + handleValues[spHandle.getSelectedItemPosition()];
            } else {
                etPayeeInput.setError("Enter a valid UPI ID (name@bank) or 10-digit mobile number");
                return;
            }

            String name = etPayeeName.getText().toString().trim();
            upiId = resolvedVpa;
            merchant = name.isEmpty() ? resolvedVpa : name;
            rawUpi = ""; // manual entry has no source QR - don't let a previous scan's params leak in
            tvMerchant.setText("Merchant: " + merchant + "\nUPI ID: " + upiId);
            tvStatus.setText(input.contains("@")
                    ? "UPI ID set. Add your note and category, then pay."
                    : "UPI ID guessed from phone number + handle - verify the payee name shown in your UPI app before approving.");
            dialog.dismiss();
        }));
        dialog.show();
    }

    /** Holds the validated form fields for a single "record this expense" action. */
    private static final class ValidatedEntry {
        final double amount; final String note; final String category;
        ValidatedEntry(double amount, String note, String category) {
            this.amount = amount; this.note = note; this.category = category;
        }
    }

    /** Validates the amount/note/category fields shared by both the pay and log-only flows. Returns null (after showing the relevant field error) if invalid. */
    private ValidatedEntry validateEntry() {
        if (upiId.isEmpty()) {
            Toast.makeText(this, "Scan a UPI QR first.", Toast.LENGTH_SHORT).show();
            return null;
        }
        // Accept a comma decimal separator too, since some device keyboards/locales emit one.
        String amountText = etAmount.getText().toString().trim().replace(',', '.');
        if (amountText.isEmpty()) {
            etAmount.setError("Enter amount");
            return null;
        }
        double amount;
        try {
            amount = Double.parseDouble(amountText);
            if (!(amount > 0) || amount > 10000000) throw new NumberFormatException();
        } catch (Exception e) {
            etAmount.setError("Enter a valid amount");
            return null;
        }

        String note = etNote.getText().toString().trim();
        if (note.isEmpty()) {
            etNote.setError("Add a note to improve your analysis");
            return null;
        }

        String category = spCategory.getSelectedItem().toString();
        return new ValidatedEntry(amount, note, category);
    }

    /**
     * For payments made directly in GPay/PhonePe/etc rather than through this app - no
     * UPI intent is launched at all, so none of the referrer-app trust issues that block
     * P2P intents apply. This just records the expense as already paid.
     */
    private void logOnly() {
        ValidatedEntry entry = validateEntry();
        if (entry == null) return;

        long id = db.insert(merchant, upiId, entry.amount, entry.note, entry.category, STATUS_LOGGED);
        etAmount.setText("");
        etNote.setText("");
        tvStatus.setText("Expense #" + id + " logged as already paid. No payment was triggered from this app.");
    }

    /**
     * Pay flow: logs the expense and builds a UPI intent using the standard payment
     * fields plus an explicit intent initiation mode and a unique transaction reference.
     * The category remains local; the optional note is sent as the UPI transaction note.
     *
     * No pre-check for installed UPI apps happens here - Android resolves the intent
     * itself (showing its own chooser if more than one app qualifies); we only find out
     * "no UPI app installed" if launching actually throws ActivityNotFoundException.
     *
     * The "did it go through?" prompt still appears automatically next time this app
     * returns to the foreground, regardless of what (if anything) the UPI app reports back -
     * response payloads across UPI apps proved inconsistent to parse reliably.
     */
    private void launchPayment() {
        ValidatedEntry entry = validateEntry();
        if (entry == null) return;

        long id = db.insert(merchant, upiId, entry.amount, entry.note, entry.category, STATUS_INITIATED);
        String amountStr = String.format(Locale.US, "%.2f", entry.amount);
        String summary = "₹" + amountStr + " to " + merchant
                + (upiId.equals(merchant) ? "" : " (" + upiId + ")");

        Uri paymentUri;
        if (rawUpi != null && !rawUpi.trim().isEmpty()) {
            try {
                Uri scannedUri = Uri.parse(rawUpi.trim());
                if (!"upi".equalsIgnoreCase(scannedUri.getScheme())
                        || !"pay".equalsIgnoreCase(scannedUri.getHost())
                        || scannedUri.getQueryParameter("pa") == null
                        || scannedUri.getQueryParameter("pa").isEmpty()) {
                    throw new IllegalArgumentException("Invalid UPI QR URI");
                }
                paymentUri = scannedUri;
            } catch (Exception e) {
                tvStatus.setText("Invalid scanned UPI QR. Please scan again.");
                return;
            }
        } else {
            Uri.Builder paymentBuilder = new Uri.Builder()
                    .scheme("upi").authority("pay")
                    .appendQueryParameter("pa", upiId)
                    .appendQueryParameter("pn", merchant)
                    .appendQueryParameter("am", amountStr)
                    .appendQueryParameter("cu", "INR");

            String upiNote = entry.note == null ? "" : entry.note.trim();
            if (!upiNote.isEmpty()) {
                if (upiNote.length() > 50) upiNote = upiNote.substring(0, 50);
                paymentBuilder.appendQueryParameter("tn", upiNote);
            }
            paymentUri = paymentBuilder.build();
        }

        android.util.Log.d("UPI_DEBUG", "PAYMENT URI: " + paymentUri);

        Intent paymentIntent = new Intent(Intent.ACTION_VIEW, paymentUri);
        List<android.content.pm.ResolveInfo> handlers =
                getPackageManager().queryIntentActivities(paymentIntent, 0);

        StringBuilder apps = new StringBuilder();
        if (handlers.isEmpty()) {
            apps.append("No installed app reports that it can handle this UPI intent.");
        } else {
            for (android.content.pm.ResolveInfo info : handlers) {
                String label = info.loadLabel(getPackageManager()).toString();
                String pkg = info.activityInfo != null ? info.activityInfo.packageName : "?";
                apps.append("✓ ").append(label).append("\n")
                        .append("  ").append(pkg).append("\n\n");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("UPI Intent Handlers")
                .setMessage("URI:\n" + paymentUri + "\n\nApps matching ACTION_VIEW + upi://pay:\n\n" + apps)
                .setPositiveButton("Continue to UPI", (d, w) ->
                        launchUpiChooser(id, summary, paymentUri))
                .setNegativeButton("Cancel", (d, w) -> {
                    pendingExpenseId = -1;
                    awaitingManualConfirmation = false;
                    db.updateStatus(id, STATUS_INITIATED, null, null);
                    tvStatus.setText("Payment cancelled. Expense #" + id + " remains initiated.");
                })
                .show();
    }

    private void launchUpiChooser(long id, String summary, Uri paymentUri) {
        // Wrapped in createChooser so the picker always appears, even if a default UPI
        // app was previously set - no resolveActivity()/introspection check beforehand,
        // since that class of call proved unreliable for "is anything installed?" earlier
        // in this project. The try/catch here is the only signal we rely on.
        Intent chooser = Intent.createChooser(new Intent(Intent.ACTION_VIEW, paymentUri), "Pay with UPI");
        try {
            pendingExpenseId = id;
            pendingSummary = "Pay " + summary + "\n\nDid it go through?";
            awaitingManualConfirmation = true;
            upiPaymentLauncher.launch(chooser);
            tvStatus.setText("Choose a UPI app for " + summary
                    + ". Expense #" + id + " is logged - confirm the outcome when you come back.");
        } catch (Exception e) {
            pendingExpenseId = -1;
            awaitingManualConfirmation = false;
            db.updateStatus(id, STATUS_INITIATED, null, null); // leave as pending, not failed - nothing was attempted
            tvStatus.setText("No UPI app could handle this. Expense #" + id
                    + " is still logged - pay manually in your UPI app and use \"Already paid elsewhere\" to confirm it.");
        }
    }

    private void showHistory() {
        android.database.Cursor c = db.all();
        StringBuilder s = new StringBuilder();
        double confirmedTotal = 0;
        int pendingCount = 0, failedCount = 0;
        HashMap<String, Double> byCat = new HashMap<>();
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

        while (c.moveToNext()) {
            double amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
            String cat = c.getString(c.getColumnIndexOrThrow("category"));
            String status = c.getString(c.getColumnIndexOrThrow("status"));

            // Confirmed payments - either verified via the UPI app's callback, or paid
            // outside this app and just logged - count toward spend totals/analysis.
            if (STATUS_SUCCESS.equals(status) || STATUS_LOGGED.equals(status)) {
                confirmedTotal += amount;
                byCat.put(cat, byCat.getOrDefault(cat, 0.0) + amount);
            } else if (STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status)) {
                failedCount++;
            } else {
                pendingCount++;
            }

            s.append("₹").append(String.format(Locale.US, "%.2f", amount))
                    .append(" • ").append(c.getString(c.getColumnIndexOrThrow("merchant")))
                    .append("\n").append(cat).append(" • ")
                    .append(c.getString(c.getColumnIndexOrThrow("note")))
                    .append("\n").append(fmt.format(new Date(c.getLong(c.getColumnIndexOrThrow("created_at")))))
                    .append(" • ").append(status)
                    .append("\n\n");
        }
        c.close();

        if (s.length() == 0) s.append("No expenses recorded yet.");
        StringBuilder summary = new StringBuilder();
        summary.append("CONFIRMED SPEND: ₹").append(String.format(Locale.US, "%.2f", confirmedTotal)).append("\n");
        summary.append("Pending: ").append(pendingCount).append("  •  Failed/Cancelled: ").append(failedCount).append("\n\n");
        summary.append("BY CATEGORY (confirmed only)\n");
        if (byCat.isEmpty()) summary.append("• None yet\n");
        for (Map.Entry<String, Double> e : byCat.entrySet())
            summary.append("• ").append(e.getKey()).append(": ₹")
                    .append(String.format(Locale.US, "%.2f", e.getValue())).append("\n");
        summary.append("\nTRANSACTIONS\n").append(s);

        android.view.View view = getLayoutInflater().inflate(R.layout.dialog_history, null);
        ((TextView) view.findViewById(R.id.tvHistory)).setText(summary.toString());
        new AlertDialog.Builder(this)
                .setTitle("Expenses & Analysis")
                .setView(view)
                .setPositiveButton("Close", null)
                .show();
    }
}
