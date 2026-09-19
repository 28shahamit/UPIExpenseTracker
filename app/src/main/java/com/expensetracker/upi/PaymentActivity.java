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
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.IOException;
import java.util.*;

public class PaymentActivity extends BaseActivity {

    private static final String STATUS_INITIATED = "INITIATED";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_PENDING = "PENDING"; // opened a UPI app, outcome unclear
    private static final String STATUS_LOGGED = "LOGGED"; // paid outside this app (e.g. directly in GPay), just recorded

    private TextView tvMerchant, tvStatus, tvAccountBanner, tvScreenSubtitle, tvStep1Title, tvStep3Title;
    private EditText etAmount, etNote, etLogPayeeName;
    private Spinner spCategory;
    private View groupQrPayee;
    private Button btnTogglePayeeMethod, btnLogPrimary, btnLogOnly, btnPay, btnPayInApp;
    // True when this screen was opened from "Log an expense" rather than "New payment" -
    // same screen/DB logic either way, but applyScreenMode() below reshapes which controls
    // are shown so the two entry points don't look like duplicates of each other.
    private boolean logMode = false;
    // Backing list for spCategory: CategoryStore's categories plus a trailing sentinel row
    // used to trigger the "add new category" dialog - see setupCategorySpinner().
    private List<String> categorySpinnerItems;
    private int lastCategoryPosition = 0;
    private static final String ADD_CATEGORY_SENTINEL = "+ Add new category";
    private String upiId = "", merchant = "", rawUpi = "";
    // Heuristic flag on the most recently scanned QR: does it look like a merchant/business
    // terminal rather than a personal P2P address? Merchant QRs carry a merchant category
    // code (mc), an explicit intent mode of "02", an org id, and/or a cryptographic sign
    // over the QR's own fields - fields a bank's fraud engine expects to see *unmodified*.
    // Appending our own "am" to a QR carrying these gets read as tampering and blocked
    // (or held pending) by the receiving app/bank, even though the same append is harmless
    // on a plain personal VPA. See launchPayment() for where this changes what we send.
    private boolean qrIsMerchant = false;
    // All query parameters from the most recently scanned QR (empty for manual entry).
    // Merchant QR codes - especially verified/dynamic ones - often carry fields beyond
    // pa/pn/am (mc, mode, purpose, orgid, sign, tid...) that a bank's risk engine requires
    // for a merchant-class transaction to clear. Keeping the full set lets launchPayment()
    // forward them instead of silently dropping them.
    private final Map<String, String> qrParams = new LinkedHashMap<>();
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
    // signal we rely on.
    //
    // The result callback below DOES now attempt to read back what the UPI app reported
    // (see parseUpiResponse()) and auto-logs the outcome when that's conclusive - but most
    // UPI apps still don't return anything usable, so the manual "did it go through?" prompt
    // in onResume()/showPaymentConfirmationDialog() remains the fallback for everything else.
    private final ActivityResultLauncher<Intent> upiPaymentLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Boolean success = parseUpiResponse(result.getData());
                if (success != null && pendingExpenseId != -1) {
                    long id = pendingExpenseId;
                    db.updateStatus(id, success ? STATUS_SUCCESS : STATUS_FAILED, null, null);
                    pendingExpenseId = -1;
                    awaitingManualConfirmation = false; // the UPI app already told us - don't also ask
                    tvStatus.setText("Expense #" + id + (success
                            ? " confirmed as paid - your UPI app reported success."
                            : " marked failed - your UPI app reported it didn't go through."));
                    refreshAccountBanner();
                }
                // Else: leave awaitingManualConfirmation as launchPayment() set it, so
                // onResume() asks - nothing conclusive came back.
            });

    /**
     * Best-effort read of what the UPI app handed back after we start it with
     * startActivityForResult(). Per NPCI's UPI Linking Specification, an app that chooses to
     * report an outcome does so via a single "response" extra containing URL-encoded
     * key=value pairs, the key one being Status=SUCCESS/FAILURE/SUBMITTED. In practice a lot
     * of UPI apps return nothing at all here (verified experimentally - see the class-level
     * comments on upiPaymentLauncher/launchPayment), so returning null just means "unknown",
     * not "failed" - the manual confirmation dialog is what covers that case.
     */
    private Boolean parseUpiResponse(Intent data) {
        if (data == null) return null;
        String response = data.getStringExtra("response");
        if (response == null) response = data.getStringExtra("Response"); // a few apps vary the case
        if (response == null || response.isEmpty()) return null;
        if (response.startsWith("?")) response = response.substring(1);
        for (String pair : response.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && "status".equalsIgnoreCase(kv[0])) {
                if ("SUCCESS".equalsIgnoreCase(kv[1])) return true;
                if ("FAILURE".equalsIgnoreCase(kv[1]) || "FAILED".equalsIgnoreCase(kv[1])) return false;
                return null; // SUBMITTED/PENDING/anything else - still ask the user
            }
        }
        return null;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment);
        db = new ExpenseDbHelper(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        if (savedInstanceState != null) {
            pendingExpenseId = savedInstanceState.getLong("pendingExpenseId", -1);
            awaitingManualConfirmation = savedInstanceState.getBoolean("awaitingManualConfirmation", false);
            pendingSummary = savedInstanceState.getString("pendingSummary", "");
            upiId = savedInstanceState.getString("upiId", "");
            merchant = savedInstanceState.getString("merchant", "");
            qrIsMerchant = savedInstanceState.getBoolean("qrIsMerchant", false);
        }

        tvMerchant = findViewById(R.id.tvMerchant);
        tvStatus = findViewById(R.id.tvStatus);
        tvAccountBanner = findViewById(R.id.tvAccountBanner);
        tvScreenSubtitle = findViewById(R.id.tvScreenSubtitle);
        tvStep1Title = findViewById(R.id.tvStep1Title);
        tvStep3Title = findViewById(R.id.tvStep3Title);
        etAmount = findViewById(R.id.etAmount);
        etNote = findViewById(R.id.etNote);
        etLogPayeeName = findViewById(R.id.etLogPayeeName);
        spCategory = findViewById(R.id.spCategory);
        groupQrPayee = findViewById(R.id.groupQrPayee);
        btnTogglePayeeMethod = findViewById(R.id.btnTogglePayeeMethod);
        btnLogPrimary = findViewById(R.id.btnLogPrimary);
        btnLogOnly = findViewById(R.id.btnLogOnly);
        btnPay = findViewById(R.id.btnPay);
        btnPayInApp = findViewById(R.id.btnPayInApp);
        setupCategorySpinner();

        findViewById(R.id.btnManageAccounts).setOnClickListener(v ->
                startActivity(new Intent(PaymentActivity.this, AccountsActivity.class)));

        findViewById(R.id.btnScan).setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                startScanner();
            else cameraPermission.launch(Manifest.permission.CAMERA);
        });
        findViewById(R.id.btnGallery).setOnClickListener(v -> galleryLauncher.launch("image/*"));
        findViewById(R.id.btnManual).setOnClickListener(v -> showManualEntryDialog());
        btnPay.setOnClickListener(v -> launchPayment());
        btnPayInApp.setOnClickListener(v -> openUpiAppToPayManually());
        btnLogOnly.setOnClickListener(v -> logOnly());
        btnLogPrimary.setOnClickListener(v -> logOnly());
        btnTogglePayeeMethod.setOnClickListener(v -> {
            groupQrPayee.setVisibility(View.VISIBLE);
            btnTogglePayeeMethod.setVisibility(View.GONE);
        });

        // "mode"="log" is how the dashboard's "Log an expense" card opens this screen now;
        // the old scroll-to-button extra is still honored so nothing breaks if some other
        // caller still sends it.
        logMode = "log".equals(getIntent().getStringExtra("mode"))
                || getIntent().getBooleanExtra("focus_log_only", false);
        applyScreenMode();

        refreshAccountBanner();
    }

    /**
     * "New payment" and "Log an expense" share one screen and one DB write path (logOnly()),
     * but they used to look identical apart from an auto-scroll - confusing, since only one
     * of them actually sends money. This reshapes the same layout instead of duplicating it:
     * log mode swaps the QR-first payee step for a plain optional name field (with the QR/UPI-ID
     * option still one tap away for anyone who wants it) and collapses step 3 down to a single
     * "Log expense" action, dropping the pay buttons that don't apply here.
     */
    private void applyScreenMode() {
        ((Toolbar) findViewById(R.id.toolbar)).setTitle(logMode ? "Log an Expense" : "New Payment");

        tvScreenSubtitle.setText(logMode
                ? "Record something you already paid \u2014 no payment is sent from this screen."
                : "Scan a QR, or enter a UPI ID, then pay.");

        tvStep1Title.setText(logMode ? "Payee (optional)" : "Payee");
        tvStep3Title.setText(logMode ? "Log it" : "Confirm & pay");

        findViewById(R.id.tilLogPayeeName).setVisibility(logMode ? View.VISIBLE : View.GONE);
        // Pay mode always needs the QR/manual-entry controls; log mode starts with them
        // collapsed behind btnTogglePayeeMethod since most "already paid" entries don't need
        // one - unless a payee was already scanned/entered (e.g. restored after process death).
        boolean expandQrGroup = !logMode || !upiId.isEmpty();
        btnTogglePayeeMethod.setVisibility(logMode && !expandQrGroup ? View.VISIBLE : View.GONE);
        groupQrPayee.setVisibility(expandQrGroup ? View.VISIBLE : View.GONE);

        btnLogPrimary.setVisibility(logMode ? View.VISIBLE : View.GONE);
        btnPay.setVisibility(logMode ? View.GONE : View.VISIBLE);
        btnPayInApp.setVisibility(logMode ? View.GONE : View.VISIBLE);
        btnLogOnly.setVisibility(logMode ? View.GONE : View.VISIBLE);
    }

    /** Shows which account new expenses will be auto-billed to, and its current balance
     * (negative shown in red - an allowed overdraft state, not an error). Called on every
     * resume since a transaction just made from this screen, or an edit made in
     * AccountsActivity/HistoryActivity, can change the balance shown here. */
    private void refreshAccountBanner() {
        Account account = db.getDefaultAccount();
        if (account == null) {
            tvAccountBanner.setText("No account set up - expenses won't be tracked against a balance.");
            tvAccountBanner.setTextColor(getColor(R.color.on_surface_secondary));
        } else {
            String balanceStr = String.format(Locale.US, "%.2f", account.balance);
            tvAccountBanner.setText("Paying from " + account.name + " \u00B7 \u20B9" + balanceStr);
            tvAccountBanner.setTextColor(getColor(account.balance < 0 ? R.color.status_failed : R.color.on_surface_secondary));
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("pendingExpenseId", pendingExpenseId);
        outState.putBoolean("awaitingManualConfirmation", awaitingManualConfirmation);
        outState.putString("pendingSummary", pendingSummary);
        outState.putString("upiId", upiId);
        outState.putString("merchant", merchant);
        outState.putBoolean("qrIsMerchant", qrIsMerchant);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAccountBanner();
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
                    refreshAccountBanner();
                })
                .setNegativeButton("Failed / didn't pay", (d, w) -> {
                    db.updateStatus(id, STATUS_FAILED, null, null);
                    pendingExpenseId = -1;
                    tvStatus.setText("Expense #" + id + " marked failed and excluded from totals.");
                    refreshAccountBanner();
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
        qrParams.clear();
        try {
            Uri u = Uri.parse(rawUpi);
            if (!"upi".equalsIgnoreCase(u.getScheme()) || !"pay".equalsIgnoreCase(u.getHost())) {
                throw new IllegalArgumentException();
            }
            upiId = u.getQueryParameter("pa");
            merchant = u.getQueryParameter("pn");
            if (upiId == null || upiId.isEmpty()) throw new IllegalArgumentException();
            if (merchant == null || merchant.isEmpty()) merchant = upiId;
            // Capture every field the QR carried, not just pa/pn/am - see the qrParams
            // field comment for why (merchant mc/mode/purpose/orgid/sign/tid...).
            for (String key : u.getQueryParameterNames()) {
                String val = u.getQueryParameter(key);
                if (val != null) qrParams.put(key, val);
            }
            qrIsMerchant = qrParams.containsKey("mc") || "02".equals(qrParams.get("mode"))
                    || qrParams.containsKey("sign") || qrParams.containsKey("orgid");
            tvMerchant.setText("Merchant: " + merchant + "\nUPI ID: " + upiId);
            String presetAmount = u.getQueryParameter("am");
            if (presetAmount != null && !presetAmount.isEmpty() && etAmount.getText().length() == 0)
                etAmount.setText(presetAmount);
            tvStatus.setText(qrIsMerchant
                    ? "Merchant QR scanned. Add your note and category - you'll enter the amount "
                    + "yourself inside your UPI app when you pay (this QR's own security fields "
                    + "won't be touched)."
                    : "QR scanned. Add your note and category, then pay.");
        } catch (Exception e) {
            upiId = "";
            merchant = "";
            rawUpi = "";
            qrParams.clear();
            qrIsMerchant = false;
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
            qrParams.clear();
            qrIsMerchant = false; // manual entry is always treated as a plain P2P address
            tvMerchant.setText("Merchant: " + merchant + "\nUPI ID: " + upiId);
            // The generic "@upi" handle is only a fallback guess, not the recipient's real
            // bank/PSP handle - banks' risk engines frequently decline P2P transfers sent to
            // it ("payment failed as per UPI risk policy" / "declined by the bank"), even when
            // the payee name still resolves correctly. Prefer the recipient's actual UPI ID or QR.
            tvStatus.setText(input.contains("@")
                    ? "UPI ID set. Add your note and category, then pay."
                    : "UPI ID guessed from phone number + handle - verify the payee name shown in your UPI app before approving. "
                    + "If you kept the default \"@upi\" handle and the payment gets declined, ask the recipient for their real "
                    + "UPI ID/QR or pick their actual bank (e.g. @okhdfcbank, @ybl) instead of guessing.");
            dialog.dismiss();
        }));
        dialog.show();
    }

    /** Populates spCategory from CategoryStore plus a trailing "+ Add new category" row, and
     * wires selecting that row to open showAddCategoryDialog() instead of actually selecting it. */
    private void setupCategorySpinner() {
        refreshCategorySpinner(null);
        spCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == categorySpinnerItems.size() - 1) {
                    // Revert to the last real category first so the sentinel is never left
                    // selected (e.g. if the user backs out of the dialog without adding one).
                    spCategory.setSelection(lastCategoryPosition, false);
                    showAddCategoryDialog();
                } else {
                    lastCategoryPosition = position;
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** Rebuilds the spinner's item list from CategoryStore. If selectCategory is non-null and
     * present in the list, it's selected; otherwise the first item is. */
    private void refreshCategorySpinner(String selectCategory) {
        categorySpinnerItems = new ArrayList<>(CategoryStore.allCategories(this));
        categorySpinnerItems.add(ADD_CATEGORY_SENTINEL);
        spCategory.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, categorySpinnerItems));
        int sel = 0;
        if (selectCategory != null) {
            int idx = categorySpinnerItems.indexOf(selectCategory);
            if (idx >= 0) sel = idx;
        }
        lastCategoryPosition = sel;
        spCategory.setSelection(sel, false);
    }

    private void showAddCategoryDialog() {
        TextInputLayout til = inflateDialogInput("Category name (e.g. Pets)");
        new AlertDialog.Builder(this)
                .setTitle("New category")
                .setView(til)
                .setPositiveButton("Add", (d, w) -> {
                    String name = til.getEditText().getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter a category name.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!CategoryStore.addCategory(this, name)) {
                        Toast.makeText(this, "\"" + name + "\" already exists.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    refreshCategorySpinner(name);
                    Toast.makeText(this, "Category \"" + name + "\" added.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Holds the validated form fields for a single "record this expense" action. */
    private static final class ValidatedEntry {
        final double amount; final String note; final String category;
        ValidatedEntry(double amount, String note, String category) {
            this.amount = amount; this.note = note; this.category = category;
        }
    }

    /** Validates the amount/note/category fields shared by the pay and log-only flows, requiring a payee to already be set. */
    private ValidatedEntry validateEntry() {
        return validateEntry(true);
    }

    /**
     * @param requirePayee false for openUpiAppToPayManually(): that flow's whole point is
     * to scan the merchant's QR *inside* the chosen UPI app, not in ours first - requiring
     * a payee here would mean scanning the same QR twice. Every other caller still needs
     * pa/pn/qrParams already populated to build or record the payment, so they pass true
     * (via the no-arg overload above).
     */
    private ValidatedEntry validateEntry(boolean requirePayee) {
        if (requirePayee && upiId.isEmpty()) {
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
        // requirePayee=false: "already paid elsewhere" is precisely the case where nothing
        // was scanned/entered here - the user paid by cash, bank transfer, or a UPI app
        // directly, and just wants it recorded. Forcing a QR scan first would defeat that.
        ValidatedEntry entry = validateEntry(false);
        if (entry == null) return;

        if (!upiId.isEmpty()) {
            // A real payee was scanned/entered (via groupQrPayee, in either mode).
            finishLog(db.insert(merchant, upiId, entry.amount, entry.note, entry.category, STATUS_LOGGED));
            return;
        }

        if (logMode) {
            // Log mode's primary payee field is the plain name box on-screen - no need for
            // a popup here, since there's nowhere else the name could have come from.
            String name = etLogPayeeName.getText().toString().trim();
            finishLog(db.insert(name.isEmpty() ? "Unknown" : name, "", entry.amount,
                    entry.note, entry.category, STATUS_LOGGED));
            return;
        }

        // Pay mode's "already paid elsewhere" escape hatch, with no payee captured yet -
        // ask for a quick optional name so the history entry isn't blank.
        showQuickPayeeDialog(entry);
    }

    private void finishLog(long id) {
        etAmount.setText("");
        etNote.setText("");
        etLogPayeeName.setText("");
        tvStatus.setText("Expense #" + id + " logged as already paid. No payment was triggered from this app.");
        refreshAccountBanner();
    }

    private void showQuickPayeeDialog(ValidatedEntry entry) {
        TextInputLayout til = inflateDialogInput("Payee name (optional)");
        new AlertDialog.Builder(this)
                .setTitle("Who did you pay?")
                .setMessage("Optional - helps this entry show up correctly in your history.")
                .setView(til)
                .setPositiveButton("Log expense", (d, w) -> {
                    String name = til.getEditText().getText().toString().trim();
                    finishLog(db.insert(name.isEmpty() ? "Unknown" : name, "", entry.amount,
                            entry.note, entry.category, STATUS_LOGGED));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Inflates a single Material outlined text field for use as an AlertDialog's setView(),
     * so quick ad-hoc prompts (this one, the new-category dialog) look like every other input
     * in the app instead of falling back to a bare, unstyled EditText. */
    private TextInputLayout inflateDialogInput(String hint) {
        TextInputLayout til = (TextInputLayout)
                getLayoutInflater().inflate(R.layout.dialog_single_input, null);
        til.setHint(hint);
        return til;
    }

    /**
     * Pay flow: logs the expense and builds a UPI intent using the standard payment
     * fields plus an explicit intent initiation mode and a unique transaction reference.
     * The category, amount and note all stay local to this app; nothing we typed
     * ourselves is forwarded as "am"/"tn" on the deep link - see the comment further
     * down for why.
     *
     * No pre-check for installed UPI apps happens here - Android resolves the intent
     * itself (showing its own chooser if more than one app qualifies); we only find out
     * "no UPI app installed" if launching actually throws ActivityNotFoundException.
     *
     * This used to only apply to merchant QRs (qrIsMerchant), leaving "am"/"tn" on
     * personal (P2P) payments. That's what was breaking Paytm/PhonePe/GPay handling of
     * friend payments even though the same request worked fine in Navi - some UPI apps
     * are stricter than others about accepting a third-party app's own am/tn on a plain
     * intent. Now every payment - merchant or personal, QR-sourced or manually entered -
     * gets the same treatment: "am" and "tn" are only ever sent if the *source QR itself*
     * already carried them (forwarded verbatim, untouched); anything typed into this app's
     * own fields is recorded locally only, and the user re-enters amount/note by hand in
     * whichever UPI app they land in.
     *
     * The "did it go through?" prompt still appears next time this app returns to the
     * foreground UNLESS upiPaymentLauncher's callback already got a conclusive answer from
     * the UPI app itself (see parseUpiResponse()) - most apps don't return one, so this
     * fallback still matters, but when one does, we log the outcome automatically instead
     * of asking.
     */
    private void launchPayment() {
        ValidatedEntry entry = validateEntry();
        if (entry == null) return;

        long id = db.insert(merchant, upiId, entry.amount, entry.note, entry.category, STATUS_INITIATED);
        String amountStr = String.format(Locale.US, "%.2f", entry.amount);
        String summary = "₹" + amountStr + " to " + merchant
                + (upiId.equals(merchant) ? "" : " (" + upiId + ")");

        // Build the UPI intent starting from whatever the source QR actually contained.
        // Merchant QR codes - especially verified/dynamic ones - carry fields beyond
        // pa/pn/am (mc = merchant category code, mode/purpose/orgid, and sometimes a
        // cryptographic sign over the QR's own fields) that bank risk engines require for
        // a merchant-class transaction to clear. The previous build discarded all of this
        // and synthesized a fresh minimal pa/pn/tr/am/cu/tn URI - invisible for personal
        // P2P QR codes (which normally carry nothing beyond pa/pn/am) but silently broke
        // merchant payments (missing mc, invalidated sign, mismatched tr). Manual entry has
        // no source QR, so qrParams is empty and this falls back to the minimal set below.
        Uri.Builder paymentBuilder = new Uri.Builder().scheme("upi").authority("pay");
        if (!rawUpi.isEmpty() && !qrParams.isEmpty()) {
            for (Map.Entry<String, String> param : qrParams.entrySet()) {
                String key = param.getKey();
                // pa/pn/am/cu/tr/tn are set explicitly below, using the current form state
                // (and, for tr/tn, the logic that follows) - skip them here to avoid
                // appending each one twice.
                if (key.equals("pa") || key.equals("pn") || key.equals("am") || key.equals("cu")
                        || key.equals("tr") || key.equals("tn")) continue;
                paymentBuilder.appendQueryParameter(key, param.getValue());
            }
        }
        paymentBuilder.appendQueryParameter("pa", upiId);
        paymentBuilder.appendQueryParameter("pn", merchant);

        // A merchant-flagged QR (mc/mode=02/sign/orgid present) is the clearest case where a
        // bank's risk engine expects those merchant fields to arrive unmodified alongside a
        // static "please tell me the amount" invitation - appending our own "am" on top of
        // them reads as tampering and gets blocked or held pending. But the same class of
        // problem showed up on plain personal (P2P) payments too - PhonePe/Paytm/GPay
        // rejecting or restricting a request that carries an amount/note this app supplied,
        // even with no merchant fields involved, while Navi accepted the identical request.
        // So "am" is never sent as something *we* computed, for any payment: only forward it
        // if the source QR already had its own "am" (a dynamic QR with a fixed amount is
        // trusted as-is, same principle as tr/tn below). Otherwise the UPI app opens without
        // a pre-filled amount and the user types ₹<amountStr> in themselves, same as if
        // they'd opened it directly. The amount is still recorded in this app's own expense
        // row either way - only what's sent in the deep link changes.
        String am = qrParams.get("am");
        if (am != null && !am.isEmpty()) {
            paymentBuilder.appendQueryParameter("am", am);
        }
        paymentBuilder.appendQueryParameter("cu", "INR");

        // NPCI's UPI Linking Specification requires a unique transaction reference (`tr`)
        // per attempt - but when the QR already supplied its own tr (common on merchant
        // QR, and load-bearing on a signed dynamic one, since the sign is computed over the
        // QR's original fields including tr), that value must be preserved as-is rather
        // than replaced. Only synthesize a fresh one when the QR had none (or for manual
        // entry) - this is still what prevents the "declined by bank" pattern from repeat
        // P2P transfers looking like duplicates/replays to the PSP.
        String tr = qrParams.get("tr");
        if (tr == null || tr.isEmpty()) {
            tr = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        }
        paymentBuilder.appendQueryParameter("tr", tr);

        // tn (transaction note) gets the same treatment as am above, for every payment: the
        // general forwarding loop skips "tn" (it's in that skip-list alongside pa/pn/am/cu/tr)
        // so it doesn't get appended twice, and this block only ever forwards the QR's own
        // tn untouched - it no longer substitutes in whatever the user typed in this app's
        // note field, merchant QR or not. Injecting our own text was very likely what broke
        // Paytm/PhonePe/GPay handling of both merchant QRs (reads as tampering alongside
        // mc/sign) and plain friend payments (same rejection, no merchant fields involved) -
        // Navi tolerated it, the others didn't. The user's note is still saved to this app's
        // own expense row regardless; it's just never pushed onto the deep link.
        String tn = qrParams.get("tn");
        if (tn != null && !tn.isEmpty()) {
            paymentBuilder.appendQueryParameter("tn", tn);
        }
        Uri paymentUri = paymentBuilder.build();
        android.util.Log.d("UPI_DEBUG", "UPI URI: " + paymentUri);
        // Logged against this expense row so it's viewable later from History -> tap the row
        // -> "View request details" - without that, diagnosing a rejection (like Paytm's
        // "payment mode not allowed for this UPI ID") means re-triggering the payment with
        // adb/Logcat attached, which isn't realistic once you're away from a computer.
        db.logOutgoingRequest(id, tr, paymentUri.toString(), rawUpi);

        // Wrapped in createChooser so the picker always appears, even if a default UPI
        // app was previously set - no resolveActivity()/introspection check beforehand,
        // since that class of call proved unreliable for "is anything installed?" earlier
        // in this project. The try/catch here is the only signal we rely on.
        Intent chooser = Intent.createChooser(new Intent(Intent.ACTION_VIEW, paymentUri), "Pay with UPI");
        // Whether the amount actually made it onto the link (only true if the source QR
        // supplied its own "am" - see the am block above); drives the messaging below.
        boolean amountSentInLink = am != null && !am.isEmpty();
        try {
            pendingExpenseId = id;
            pendingSummary = (!amountSentInLink
                    ? "Pay " + summary + " (you'll type the amount yourself in the app)"
                    : "Pay " + summary) + "\n\nDid it go through?";
            awaitingManualConfirmation = true;
            upiPaymentLauncher.launch(chooser);
            // If a note was typed but nothing ended up in tn (no QR-supplied one, and we no
            // longer substitute the user's own text), say so - or the note silently not
            // reaching the UPI app looks like a regression instead of a deliberate choice.
            boolean noteDropped = (tn == null || tn.isEmpty())
                    && entry.note != null && !entry.note.trim().isEmpty();
            String noteCaveat = noteDropped
                    ? " Your note is saved here but not sent to the UPI app." : "";
            tvStatus.setText(!amountSentInLink
                    ? "Choose a UPI app and enter ₹" + amountStr + " for " + merchant
                    + " yourself." + noteCaveat + " Expense #" + id + " is logged - confirm the outcome when you come back."
                    : "Choose a UPI app for " + summary
                    + "." + noteCaveat + " Expense #" + id + " is logged - confirm the outcome when you come back.");
        } catch (Exception e) {
            pendingExpenseId = -1;
            awaitingManualConfirmation = false;
            db.updateStatus(id, STATUS_INITIATED, null, null); // leave as pending, not failed - nothing was attempted
            tvStatus.setText("No UPI app could handle this. Expense #" + id
                    + " is still logged - pay manually in your UPI app and use \"Already paid elsewhere\" to confirm it.");
        }
    }

    // Display name -> package name for UPI apps this app knows how to hand off to directly
    // (as opposed to via a upi://pay intent). Checked against what's actually installed at
    // button-press time - not queried up front, for the same reliability reason noted on
    // startScanner()/launchPayment() above.
    private static final String[][] KNOWN_UPI_APPS = {
            {"Google Pay", "com.google.android.apps.nbu.paisa.user"},
            {"PhonePe", "com.phonepe.app"},
            {"Paytm", "net.one97.paytm"},
            {"Navi", "com.naviapp"},
            {"BHIM", "in.org.npci.upiapp"},
            {"Amazon Pay", "in.amazon.mShop.android.shopping"},
            {"WhatsApp", "com.whatsapp"},
            {"Cred", "com.dreamplug.androidapp"},
    };

    /**
     * Alternative to launchPayment(): instead of building a upi://pay intent ourselves,
     * this just logs the expense and opens the chosen UPI app's own home screen so the
     * user scans the merchant's QR (or searches the payee) inside that app directly, using
     * its own native flow end to end - the same as if this tracker app didn't exist.
     *
     * This sidesteps anything specific to how *we* construct the payment request (missing
     * fields, an app's handling of a third-party intent, etc). It does NOT help with a
     * failure the receiving bank itself reports (e.g. "receiver's bank account is facing
     * some issue") - that decline happens after the payment reaches NPCI, independent of
     * which app or method initiated it.
     *
     * Confirmation on return reuses the same "did it go through?" flow as launchPayment().
     */
    private void openUpiAppToPayManually() {
        // requirePayee=false: this flow's entire purpose is to scan the merchant's QR
        // inside the chosen UPI app, so a payee scanned/entered here first is optional,
        // not required - see the javadoc on validateEntry(boolean).
        ValidatedEntry entry = validateEntry(false);
        if (entry == null) return;

        List<String[]> installed = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (String[] app : KNOWN_UPI_APPS) {
            if (pm.getLaunchIntentForPackage(app[1]) != null) installed.add(app);
        }
        if (installed.isEmpty()) {
            Toast.makeText(this, "No supported UPI app found installed.", Toast.LENGTH_LONG).show();
            return;
        }

        String amountStr = String.format(Locale.US, "%.2f", entry.amount);
        // Only mention the payee if one is actually known (i.e. the user scanned/entered
        // one here for some other reason) - normally it isn't, since they're about to
        // scan the merchant's QR inside the other app instead.
        boolean havePayee = !upiId.isEmpty();
        String summary = "₹" + amountStr + (havePayee
                ? " to " + merchant + (upiId.equals(merchant) ? "" : " (" + upiId + ")")
                : "");

        String[] labels = new String[installed.size()];
        for (int i = 0; i < installed.size(); i++) labels[i] = installed.get(i)[0];

        // Only log the expense once the user actually picks an app to open - cancelling
        // this dialog should leave no trace, same as cancelling the QR scanner does.
        new AlertDialog.Builder(this)
                .setTitle("Open which app?")
                .setItems(labels, (d, which) -> {
                    String pkg = installed.get(which)[1];
                    Intent launch = pm.getLaunchIntentForPackage(pkg);
                    if (launch == null) {
                        // Uninstalled between the check above and the tap - rare.
                        Toast.makeText(this, labels[which] + " is no longer available.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    // Fall back to the app name so the history row isn't blank when no
                    // payee is known yet - the note the user typed still carries the real
                    // "what this was for" description.
                    String recordMerchant = havePayee ? merchant : "Paid via " + labels[which];
                    long id = db.insert(recordMerchant, upiId, entry.amount, entry.note, entry.category, STATUS_INITIATED);
                    pendingExpenseId = id;
                    pendingSummary = "Pay " + summary + " in " + labels[which]
                            + " - scan the merchant's QR there\n\nDid it go through?";
                    awaitingManualConfirmation = true;
                    startActivity(launch);
                    tvStatus.setText("Opened " + labels[which] + " for " + summary
                            + ". Scan the merchant's QR there. Expense #" + id
                            + " is logged - confirm the outcome when you come back.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // Expense history + category analysis now lives in HistoryActivity, backed by
    // ExpenseDbHelper#getAllExpenses() and an Expense/ExpenseAdapter pair instead of a
    // single hand-built string - see HistoryActivity.java.
}
