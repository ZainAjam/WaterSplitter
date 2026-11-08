# Google Drive backup: one-time Google Cloud setup

The app keeps a backup (`water-splitter-backup.zip`, one file) in a Google Drive folder called "Water Splitter data backup" after every
completed month. It connects to Drive itself (no Drive app needed). Google only allows that for apps it knows about, so the project
owner registers the app once. Nothing secret goes into the app or the repository: Google recognises the app by its package name and
the fingerprint of the key it is signed with.

The app asks for the narrow `drive.file` permission: it can see only the folder and file it created, never the rest of the Drive.

## Setup (about 10 minutes, once)

1. Go to <https://console.cloud.google.com> and sign in with the Google account that should own the project.
   Create a project, e.g. **Water Splitter**.
2. **APIs & Services -> Library -> Google Drive API -> Enable.**
3. **Google Auth Platform** (or "OAuth consent screen"):
   - App name **Water Splitter**, your email as support and contact email.
   - Audience / user type **External**.
   - **Data access -> Add or remove scopes ->** add `https://www.googleapis.com/auth/drive.file` (listed as "See, edit, create and delete
     only the specific Google Drive files you use with this app"). This scope is non-sensitive, so Google does not need to review the app.
   - **Audience -> Publish app** (status "In production"). While the status is "Testing", Google withdraws the permission every 7 days.
4. **Clients -> Create client -> Application type: Android.** Create one client per signing key, all with package name
   `za.westend.watersplit`:
   - **Release:** SHA-1 of the key in `C:\Users\zaina\WaterSplit-signing\release.jks`. Get it from a published APK with
     `apksigner verify --print-certs water-split-<version>.apk` (the "SHA-1 digest" line).
   - **Debug** (only for testing from this computer): `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android`

Google needs a few minutes to start recognising a new client.

## What the person sees

Settings -> App Settings -> **Connect Google Drive**: Google's own screen asks which account and whether the app may use its own
files in Drive. After that every completed month refreshes the backup in the background. "Back up now" does it on demand.
If Google withdraws the permission the app says so and asks to connect again.

## Restoring

The file is the same zip as Settings -> Backup. Download `water-splitter-backup.zip` from the Drive folder to the phone, then in the app
use **Restore from a backup** (in the first-time wizard or in Settings).
