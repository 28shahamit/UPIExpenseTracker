package com.expensetracker.upi;

import android.Manifest;
import androidx.appcompat.app.AlertDialog;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.IOException;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final String STATUS_INITIATED = "INITIATED";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_PENDING = "PENDING"; // opened a UPI app, outcome unclear
    private static final String STATUS_LOGGED = "LOGGED"; // paid outside this app (e.g. directly in GPay), just recorded

    private TextView tvMerchant, tvStatus;
    private View cardStatus;
    private EditText etAmount, etNote;
    private Spinner spCategory;
    // Backing list for spCategory: CategoryStore's categories plus a trailing sentinel row
    // used to trigger the "add new category" dialog - see setupCategorySpinner().
    private List<String> categorySpinnerItems;
    private int lastCategoryPosition = 0;
    private static final String ADD_CATEGORY_SENTINEL = "+ Add new category";
    private String upiId = "", merchant = "", rawUpi = "";
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

    // Fields of whichever manual-entry dialog is currently open, and the phone number of the
    // contact just picked for it (if any) - both set when the dialog opens/a suggestion is
    // picked, read back in applyPickedContact() and the dialog's Continue handler.
    private EditText activeEtPayeeInput, activeEtPayeeName;
    private TextView activeTvContactHint, activeTvHandleLabel;
    private Spinner activeSpHandle;
    private String activeContactPhone;
    private EditText activeEtContactSearch;
    private View activeSvContactSuggestions;
    private LinearLayout activeLlContactSuggestions;
    // Loaded once per app session (first time the search field is focused with permission
    // granted) and reused across dialog opens, since re-querying the whole phonebook on every
    // "Enter UPI ID / Phone Manually" tap would be wasteful. Null = not loaded yet.
    private List<ContactEntry> cachedContacts;

    // Uses the system picker (Storage Access Framework) - no READ_MEDIA_IMAGES /
    // READ_EXTERNAL_STORAGE permission required.
    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                String contents = decodeQrFromUri(uri);
                if (contents != null) {
                    handleQr(contents);
                } else {
                    setStatus("Couldn't find a readable QR code in that photo. Try a clearer image.");
                }
            });

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startScanner();
                else Toast.makeText(this, "Camera permission is required to scan a QR.", Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<String> contactsPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) loadContactsAsync();
                else Toast.makeText(this, "Contacts permission is required to search your contacts.", Toast.LENGTH_LONG).show();
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
        BottomNav.wire(this, R.id.navHome);

        if (savedInstanceState != null) {
            pendingExpenseId = savedInstanceState.getLong("pendingExpenseId", -1);
            awaitingManualConfirmation = savedInstanceState.getBoolean("awaitingManualConfirmation", false);
            pendingSummary = savedInstanceState.getString("pendingSummary", "");
            upiId = savedInstanceState.getString("upiId", "");
            merchant = savedInstanceState.getString("merchant", "");
        }

        tvMerchant = findViewById(R.id.tvMerchant);
        tvStatus = findViewById(R.id.tvStatus);
        cardStatus = findViewById(R.id.cardStatus);
        etAmount = findViewById(R.id.etAmount);
        etNote = findViewById(R.id.etNote);
        spCategory = findViewById(R.id.spCategory);
        setupCategorySpinner();

        findViewById(R.id.rowAccount).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, AccountsActivity.class)));

        findViewById(R.id.btnScan).setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                startScanner();
            else cameraPermission.launch(Manifest.permission.CAMERA);
        });
        findViewById(R.id.btnGallery).setOnClickListener(v -> galleryLauncher.launch("image/*"));
        findViewById(R.id.btnManual).setOnClickListener(v -> showManualEntryDialog());
        findViewById(R.id.btnPay).setOnClickListener(v -> launchPayment());
        findViewById(R.id.btnPayInApp).setOnClickListener(v -> openUpiAppToPayManually());
        findViewById(R.id.btnLogOnly).setOnClickListener(v -> logOnly());
        findViewById(R.id.btnHistory).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, HistoryActivity.class)));
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
        // Covers both returning from AccountsActivity (balance/default may have changed) and
        // a payment just having been confirmed below (which also changes a balance).
        refreshAccountSummary();
        if (awaitingManualConfirmation && pendingExpenseId != -1) {
            // Clear the flag immediately so backgrounding/resuming again (e.g. the system
            // dims the screen, or the user checks another app) doesn't reshow this.
            awaitingManualConfirmation = false;
            showPaymentConfirmationDialog(pendingExpenseId, pendingSummary);
        }
    }

    /** @return the current default account's id, or null if none is set up yet - every
     * insert() call site passes this through so new expenses auto-link to it. */
    private Long getDefaultAccountId() {
        Account acc = db.getDefaultAccount();
        return acc == null ? null : acc.id;
    }

    // Shows the status card only when there is actually something to say,
    // instead of leaving an empty-looking card visible at rest.
    private void setStatus(CharSequence message) {
        tvStatus.setText(message);
        if (cardStatus != null) {
            cardStatus.setVisibility(message != null && message.length() > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshAccountSummary() {
        TextView tv = findViewById(R.id.tvAccountSummary);
        Account acc = db.getDefaultAccount();
        if (acc == null) {
            tv.setText("No account set up - tap to add one");
        } else {
            tv.setText("\uD83D\uDCB3 " + acc.name + "   " + Money.format(acc.balance));
        }
    }

    private void showPaymentConfirmationDialog(long id, String summary) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Did this payment go through?")
                .setMessage(summary)
                .setCancelable(false)
                .setPositiveButton("Paid successfully", (d, w) -> {
                    db.updateStatus(id, STATUS_SUCCESS, null, null);
                    pendingExpenseId = -1;
                    refreshAccountSummary();
                    setStatus("Expense #" + id + " confirmed as paid.");
                })
                .setNegativeButton("Failed / didn't pay", (d, w) -> {
                    db.updateStatus(id, STATUS_FAILED, null, null);
                    pendingExpenseId = -1;
                    setStatus("Expense #" + id + " marked failed and excluded from totals.");
                })
                .setNeutralButton("Not sure yet", (d, w) ->
                        setStatus("Expense #" + id + " left pending - check history to update it once you know."))
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
            else setStatus("QR scan cancelled.");
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
            tvMerchant.setText("Merchant: " + merchant + "\nUPI ID: " + upiId);
            String presetAmount = u.getQueryParameter("am");
            if (presetAmount != null && !presetAmount.isEmpty() && etAmount.getText().length() == 0)
                etAmount.setText(presetAmount);
            setStatus("QR scanned. Add your note and category, then pay.");
        } catch (Exception e) {
            upiId = "";
            merchant = "";
            rawUpi = "";
            qrParams.clear();
            tvMerchant.setText("Invalid or unsupported UPI QR");
            setStatus("Please scan a standard UPI payment QR.");
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
        TextView tvContactHint = view.findViewById(R.id.tvContactHint);
        Spinner spHandle = view.findViewById(R.id.spHandle);

        // Bridge for the async contact-search flow - see the field comments and
        // applyPickedContact(). Reset per-dialog so a stale pick from a previous, already-
        // closed dialog can never land in this one.
        activeEtPayeeInput = etPayeeInput;
        activeEtPayeeName = etPayeeName;
        activeTvContactHint = tvContactHint;
        activeTvHandleLabel = tvHandleLabel;
        activeSpHandle = spHandle;
        activeContactPhone = null;

        EditText etContactSearch = view.findViewById(R.id.etContactSearch);
        View svContactSuggestions = view.findViewById(R.id.svContactSuggestions);
        LinearLayout llContactSuggestions = view.findViewById(R.id.llContactSuggestions);
        activeEtContactSearch = etContactSearch;
        activeSvContactSuggestions = svContactSuggestions;
        activeLlContactSuggestions = llContactSuggestions;

        etContactSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshContactSuggestions(s.toString()); }
        });
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            loadContactsAsync(); // populates cachedContacts once loaded; no-op re-query if already cached
        } else {
            // Ask on first focus rather than immediately on dialog open, so a user who's just
            // going to type a UPI ID/phone by hand is never interrupted by a permission prompt
            // they don't need.
            etContactSearch.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    contactsPermission.launch(Manifest.permission.READ_CONTACTS);
                }
            });
        }

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
                // The saved-UPI-ID hint only applies to the exact contact it was shown for -
                // any manual edit to the field invalidates it (typing over it, or clearing it
                // to enter someone else's ID by hand).
                if (tvContactHint.getVisibility() == View.VISIBLE) {
                    tvContactHint.setVisibility(View.GONE);
                    activeContactPhone = null;
                }
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
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
            tvMerchant.setText("Merchant: " + merchant + "\nUPI ID: " + upiId);

            // Remember this UPI ID against the picked contact's phone number so choosing them
            // again skips re-typing/re-guessing - see ContactStore's javadoc for what this
            // does and doesn't do (it never looks a UPI ID up automatically).
            if (activeContactPhone != null) {
                ContactStore.remember(MainActivity.this, activeContactPhone, merchant, resolvedVpa);
            }

            // The generic "@upi" handle is only a fallback guess, not the recipient's real
            // bank/PSP handle - banks' risk engines frequently decline P2P transfers sent to
            // it ("payment failed as per UPI risk policy" / "declined by the bank"), even when
            // the payee name still resolves correctly. Prefer the recipient's actual UPI ID or QR.
            setStatus(input.contains("@")
                    ? "UPI ID set. Add your note and category, then pay."
                    : "UPI ID guessed from phone number + handle - verify the payee name shown in your UPI app before approving. "
                    + "If you kept the default \"@upi\" handle and the payment gets declined, ask the recipient for their real "
                    + "UPI ID/QR or pick their actual bank (e.g. @okhdfcbank, @ybl) instead of guessing.");
            dialog.dismiss();
        }));
        dialog.show();
    }

    /** Queries the device's phonebook (display name + number per row) once and caches the
     * result in cachedContacts, so reopening the manual-entry dialog doesn't re-scan it. Runs
     * off the main thread since a large contact list can take a noticeable moment to read.
     * Only called once READ_CONTACTS is confirmed granted (see contactsPermission and
     * etContactSearch's focus listener in showManualEntryDialog()). */
    private void loadContactsAsync() {
        if (cachedContacts != null) {
            refreshContactSuggestions(activeEtContactSearch == null ? "" : activeEtContactSearch.getText().toString());
            return;
        }
        new Thread(() -> {
            List<ContactEntry> loaded = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String[] projection = {
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            };
            try (android.database.Cursor c = getContentResolver().query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection,
                    null, null,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String name = c.getString(0);
                        String number = c.getString(1);
                        if (name == null || number == null) continue;
                        String normalized = ContactStore.normalize(number);
                        if (normalized.length() != 10) continue; // skip landlines/short codes/garbage rows
                        String key = name + "|" + normalized;
                        if (!seen.add(key)) continue; // same person/number synced in from two accounts
                        loaded.add(new ContactEntry(name, number));
                    }
                }
            } catch (SecurityException ignored) {
                // Permission revoked between the check and the query (rare) - just show no suggestions.
            }
            runOnUiThread(() -> {
                cachedContacts = loaded;
                refreshContactSuggestions(activeEtContactSearch == null ? "" : activeEtContactSearch.getText().toString());
            });
        }).start();
    }

    /** Fills whichever manual-entry dialog is currently open (see the active* fields) with a
     * suggestion picked from etContactSearch. If that phone number already has a UPI ID
     * remembered from a previous entry (see ContactStore), prefills the UPI ID directly
     * instead of the phone number, so handle-guessing is skipped entirely for a repeat payment. */
    private void applyPickedContact(ContactEntry contact) {
        if (contact == null || activeEtPayeeInput == null) return;
        String name = contact.name;
        String number = contact.phone;

        if (activeEtPayeeName.getText().toString().trim().isEmpty() && name != null) {
            activeEtPayeeName.setText(name);
        }

        String savedUpiId = ContactStore.upiIdFor(this, number);
        if (savedUpiId != null) {
            activeEtPayeeInput.setText(savedUpiId); // triggers the field's own watcher below first
            activeTvHandleLabel.setVisibility(View.GONE);
            activeSpHandle.setVisibility(View.GONE);
            activeTvContactHint.setText("\u2713 Using the UPI ID you saved for "
                    + (name != null ? name : "this contact") + " last time - edit above if it's changed.");
            activeTvContactHint.setVisibility(View.VISIBLE);
        } else {
            activeEtPayeeInput.setText(ContactStore.normalize(number));
            activeTvContactHint.setVisibility(View.GONE);
            Toast.makeText(this, "No saved UPI ID for " + (name != null ? name : "this contact")
                    + " yet - pick their bank below, or enter their UPI ID directly.", Toast.LENGTH_LONG).show();
        }
        // Set last: activeEtPayeeInput.setText() above runs the field's TextWatcher
        // synchronously, which clears activeContactPhone on any text change (see that
        // watcher) - this re-establishes it once that's done.
        activeContactPhone = number;
    }

    /** Rebuilds the inline suggestion list under etContactSearch from cachedContacts, filtered
     * by name/number substring. Replaces the old AutoCompleteTextView + adapter approach, whose
     * popup window rendered outside the dialog's own bounds (overlapping the search hint and
     * spilling past the dialog card) since it isn't part of the dialog's view hierarchy - an
     * inline list inside the dialog's own layout is guaranteed to stay contained and themed. */
    private static final int MAX_CONTACT_SUGGESTIONS = 30; // safety ceiling only - the list scrolls, so this rarely matters

    private void refreshContactSuggestions(String rawQuery) {
        if (activeLlContactSuggestions == null || activeSvContactSuggestions == null) return;
        activeLlContactSuggestions.removeAllViews();

        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) {
            activeSvContactSuggestions.setVisibility(View.GONE);
            return;
        }
        String qDigits = q.replaceAll("[^0-9]", "");
        List<ContactEntry> source = cachedContacts == null ? new ArrayList<>() : cachedContacts;
        List<ContactEntry> matches = new ArrayList<>();
        for (ContactEntry c : source) {
            boolean nameMatch = c.name != null && c.name.toLowerCase(Locale.getDefault()).contains(q);
            boolean numberMatch = !qDigits.isEmpty() && c.phone != null
                    && c.phone.replaceAll("[^0-9]", "").contains(qDigits);
            if (nameMatch || numberMatch) matches.add(c);
        }

        if (matches.isEmpty()) {
            activeSvContactSuggestions.setVisibility(View.GONE);
            return;
        }
        int shownCount = Math.min(matches.size(), MAX_CONTACT_SUGGESTIONS);
        for (int i = 0; i < shownCount; i++) {
            ContactEntry contact = matches.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_contact_suggestion, activeLlContactSuggestions, false);
            String initial = contact.name != null && !contact.name.isEmpty()
                    ? contact.name.substring(0, 1).toUpperCase(Locale.getDefault()) : "?";
            ((TextView) row.findViewById(R.id.tvSuggestionInitial)).setText(initial);
            ((TextView) row.findViewById(R.id.tvSuggestionName)).setText(contact.name);
            ((TextView) row.findViewById(R.id.tvSuggestionNumber)).setText(contact.phone);
            row.setOnClickListener(v -> {
                applyPickedContact(contact);
                if (activeEtContactSearch != null) activeEtContactSearch.setText("");
                activeSvContactSuggestions.setVisibility(View.GONE);
                View current = getCurrentFocus();
                if (current != null) {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(current.getWindowToken(), 0);
                }
            });
            activeLlContactSuggestions.addView(row);
        }
        if (matches.size() > shownCount) {
            TextView more = new TextView(this);
            more.setText("+" + (matches.size() - shownCount) + " more match" + (matches.size() - shownCount == 1 ? "" : "es")
                    + " - keep typing to narrow down");
            more.setTextColor(getColor(R.color.on_surface_secondary));
            more.setTextSize(12.5f);
            more.setPadding(4, 4, 4, 4);
            activeLlContactSuggestions.addView(more);
        }
        activeSvContactSuggestions.setVisibility(View.VISIBLE);
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
        EditText input = new EditText(this);
        input.setHint("e.g. Pets");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(this)
                .setTitle("New category")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String name = input.getText().toString().trim();
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

        if (upiId.isEmpty()) {
            // No payee known - ask for a quick optional name so the history entry isn't
            // blank, without requiring a full scan/manual-entry flow.
            showQuickPayeeDialog(entry);
            return;
        }
        long id = db.insert(merchant, upiId, entry.amount, entry.note, entry.category, STATUS_LOGGED, getDefaultAccountId());
        etAmount.setText("");
        etNote.setText("");
        refreshAccountSummary();
        setStatus("Expense #" + id + " logged as already paid. No payment was triggered from this app.");
    }

    private void showQuickPayeeDialog(ValidatedEntry entry) {
        EditText input = new EditText(this);
        input.setHint("e.g. Jaya Kumari (optional)");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Who did you pay?")
                .setMessage("Optional - helps this entry show up correctly in your history.")
                .setView(input)
                .setPositiveButton("Log expense", (d, w) -> {
                    String name = input.getText().toString().trim();
                    long id = db.insert(name.isEmpty() ? "Unknown" : name, "", entry.amount,
                            entry.note, entry.category, STATUS_LOGGED, getDefaultAccountId());
                    etAmount.setText("");
                    etNote.setText("");
                    refreshAccountSummary();
                    setStatus("Expense #" + id + " logged as already paid. No payment was triggered from this app.");
                })
                .setNegativeButton("Cancel", null)
                .show();
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

        long id = db.insert(merchant, upiId, entry.amount, entry.note, entry.category, STATUS_INITIATED, getDefaultAccountId());
        // amountStr feeds the actual upi://pay request built below (the "am" param) - its
        // plain "%.2f" form is exactly what NPCI/PSP apps expect and must not change. The
        // nicer, comma-grouped Money.format() below is display-only, for `summary`.
        String amountStr = String.format(Locale.US, "%.2f", entry.amount);
        String summary = Money.format(entry.amount) + " to " + merchant
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
        paymentBuilder.appendQueryParameter("am", amountStr);
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
        db.setRequestTr(id, tr); // logging only - records what we sent, doesn't change the request itself

        // Same reasoning for tn: don't clobber a merchant QR's own transaction note
        // (possibly sign-protected) with the user's personal note. Only add it when the QR
        // had none - the personal note is always kept locally in the expense record
        // regardless of what's sent to the UPI app.
        if (!qrParams.containsKey("tn")) {
            String upiNote = entry.note == null ? "" : entry.note.trim();
            if (!upiNote.isEmpty()) {
                if (upiNote.length() > 50) upiNote = upiNote.substring(0, 50);
                paymentBuilder.appendQueryParameter("tn", upiNote);
            }
        }
        Uri paymentUri = paymentBuilder.build();
        android.util.Log.d("UPI_DEBUG", "UPI URI: " + paymentUri);

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
            setStatus("Choose a UPI app for " + summary
                    + ". Expense #" + id + " is logged - confirm the outcome when you come back.");
        } catch (Exception e) {
            pendingExpenseId = -1;
            awaitingManualConfirmation = false;
            db.updateStatus(id, STATUS_INITIATED, null, null); // leave as pending, not failed - nothing was attempted
            setStatus("No UPI app could handle this. Expense #" + id
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

        // Only mention the payee if one is actually known (i.e. the user scanned/entered
        // one here for some other reason) - normally it isn't, since they're about to
        // scan the merchant's QR inside the other app instead.
        boolean havePayee = !upiId.isEmpty();
        String summary = Money.format(entry.amount) + (havePayee
                ? " to " + merchant + (upiId.equals(merchant) ? "" : " (" + upiId + ")")
                : "");

        String[] labels = new String[installed.size()];
        for (int i = 0; i < installed.size(); i++) labels[i] = installed.get(i)[0];

        // Only log the expense once the user actually picks an app to open - cancelling
        // this dialog should leave no trace, same as cancelling the QR scanner does.
        new MaterialAlertDialogBuilder(this)
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
                    long id = db.insert(recordMerchant, upiId, entry.amount, entry.note, entry.category, STATUS_INITIATED, getDefaultAccountId());
                    pendingExpenseId = id;
                    pendingSummary = "Pay " + summary + " in " + labels[which]
                            + " - scan the merchant's QR there\n\nDid it go through?";
                    awaitingManualConfirmation = true;
                    startActivity(launch);
                    setStatus("Opened " + labels[which] + " for " + summary
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
