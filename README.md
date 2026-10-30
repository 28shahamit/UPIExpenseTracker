# UPI Expense Tracker

A simple Android Java app for personal expense tracking.

## V1 flow

1. Scan a merchant's UPI QR.
2. Enter the payment amount.
3. Add a personal note.
4. Choose a category.
5. Tap **PAY WITH UPI**.
6. The app launches the installed UPI app using a `upi://pay` intent.
7. The expense is stored locally before the hand-off.

## Important behavior

The app does **not** attempt to read bank SMS, bank accounts, or UPI transaction history. It records payments initiated from this app.

The initial transaction status is `INITIATED`. The actual payment is completed in the selected UPI app. V1 deliberately does not claim that a payment succeeded merely because the UPI app opened.

## Build with GitHub

Push the project to a GitHub repository. The workflow at `.github/workflows/android-build.yml` runs on every push to `main`/`master`, on pull requests, and on manual dispatch.

It always builds an **unsigned debug APK** - no setup required.
After the workflow succeeds: GitHub → Actions → Build APK → Artifacts → `UPIExpenseTracker-debug-apk`.

It also builds a **signed release APK**, but only if these repository secrets are set (GitHub → Settings → Secrets and variables → Actions):

| Secret              | Value                                                              |
|---------------------|---------------------------------------------------------------------|
| `KEYSTORE_BASE64`   | Your `release.keystore.jks`, base64-encoded (`base64 -i release.keystore.jks \| pbcopy` or `base64 -w0 release.keystore.jks`) |
| `KEYSTORE_PASSWORD` | Your keystore password                                              |
| `KEY_ALIAS`         | Your key alias                                                      |
| `KEY_PASSWORD`      | Your key password                                                   |

Without these secrets, only the debug APK is built (the release step is skipped, not failed). With them set, the keystore itself never needs to be committed to the repo - the workflow decodes it from the secret at build time only.

> The keystore file was intentionally left out of this project when it was shared with you. Don't commit `release.keystore.jks` or hardcode its real passwords into `app/build.gradle` if this repo will ever be public or shared - use the secrets above instead.

After a successful run with secrets configured: GitHub → Actions → Build APK → Artifacts → `UPIExpenseTracker-release-apk`.

## Next useful upgrades

- Capture and interpret the UPI app result where supported.
- Edit transaction status.
- Monthly dashboard and charts.
- Search/filter by note, category and merchant.
- CSV export.
- Backup/restore.
- Recurring expense detection.
- Better QR validation and merchant metadata handling.
- Optional app lock/encryption.
