# West End Water & Refuse Calculator

Android app (Kotlin, Jetpack Compose) for a landlord to split a Nelson Mandela Bay municipal water/refuse invoice
between the outbuilding tenant, the forebuilding tenant and the owner, produce itemised PDF invoices and share
them on WhatsApp. The user is non-technical: keep the UI minimal and always let her confirm extracted numbers.
Business rules: `docs/RULES.md`. Read it before touching `Calculator.kt`.

## Layout
- `engine/` - pure Kotlin/JVM library (no Android). BigDecimal only, never Double. Fully unit tested.
- `app/` - Android app (Compose, no DI/Room: one JSON file in private storage via `data/Store.kt`, attachments under
  `files/attachments/<yyyy-MM>/`). Screens in `ui/`. Statements are drawn as a PNG (`share/StatementImage.kt`) because WhatsApp
  shows images inline but needs a PDF reader for PDFs. WhatsApp in `share/WhatsApp.kt`. The app can import the old-sheet history on
  first launch from the golden CSV (an asset straight from `engine/src/test/resources`), but that CSV is PRIVATE and git-ignored: the repo and
  the GitHub release APKs are PUBLIC and contain no rent history, account number, meter serial or address. The owner's data reaches her
  phone through Settings -> Backup (save on the test phone, restore on hers). Settings defaults for those fields are blank.
- App name is "Water Splitter" (package stays `za.westend.watersplit`). Home shows only the title + cog (dot when an update waits), "New month",
  drafts and previous months. Cog -> `SettingsScreen` hub -> Tenants, Service Settings (municipal account, meter serial + last reading,
  statement address/name) and App Settings (Claude key, reminders incl. `Settings.reminderDay`, backup, updates). Sub-screens use the
  `BackArrow` icon, never a "Back" word. `Screen` carries its own `back` target (see `AppRoot.parentOf`).
- First-time wizard (`ui/SetupScreen.kt`, 7 steps, shown while `Settings.setupDone` is false; installs that already have tenants skip it):
  1 allow installing updates + notifications (both skippable), 2 restore a backup (or "I don't have a backup"), 3 backup location (choose a folder,
  or keep the default), 4 current tenants (name + valid WhatsApp number each, or "nobody lives here"), 5 earlier tenants (optional), 6 reminders
  (day of month), 7 Claude key. A restore that brings tenants back goes 3 -> 7 (the key is never in a backup).
  A backup is ONE zip; restore is `rememberRestoreFromBackup`, shared with Settings -> Backup. See the "Backup location" and "Home banners" notes
  below for what Home shows at the top after the wizard (permissions/location banner, then the update banner).
  The updater lists `/releases` (not `/latest`) so prereleases count; every `v0.*` tag is published as a prerelease.
- Month lifecycle: a month is a **Draft** (many at once) until `DraftChecker` (engine) says both the invoice numbers and the
  meter reading are captured; only then "Review & send" unlocks and it becomes a calculated `MonthRecord`. "Edit the numbers"
  turns a month back into a draft. Sharing a file onto a month that is already calculated asks before overwriting it; onto a
  draft that already has that kind of file it asks before replacing it.
- Tenants: `Tenancy` (engine `Tenants.kt`) = one tenant in one house for a run of whole months (first month, last month or "still there").
  A month's statements go to `TenantBook.tenantFor(house, month)`; months with nobody recorded show "Tenant not recorded" (old
  sheet months stay that way until earlier tenants are added in the Tenants screen). Stays in one house may not overlap, except that
  adding a new tenant closes an earlier open-ended stay the month before. `MonthRecord` still keeps a name/number snapshot as a
  fallback. Old Settings tenants are migrated to tenancies on first start of v3 data (see `Store.migrateTenantsFromSettings`).
  "Find in my contacts" uses Android's own picker (`ui/Contacts.kt`), so no contacts permission is held.
- Attaching from the draft screen uses `share/SourceFiles.kt`: the invoice picker opens in Downloads (`primary:Download`), the meter picker in
  Pictures/WhatsApp, and the picked file's address is kept as `Attachment.sourceUri` (only when Android lets the app delete it). On "Review and
  send" a dialog offers to delete those originals (`DraftScreen.finish`); files that arrive through the share sheet have no `sourceUri` and are
  never deleted. Settings hub = Tenants, Service Settings, App Settings. "Find in my contacts" fills BOTH name and number.
- Backup location (`data/BackupLocation.kt`, UI in `ui/AlertBanners.kt` + `SettingsScreen.BackupPanel`): after every completed month (`DraftScreen.finish`) and on
  "Save a backup", ONE file `water-splitter-backup.zip` (replaced each time) is written. DEFAULT location: `Download/Water Splitter data backup/` on the phone,
  written through MediaStore (no permission, Android 10+). She can instead pick a folder once in Android's folder picker (`OpenDocumentTree`: phone
  storage, Google Drive, OneDrive...); the app creates/uses a folder "Water Splitter data backup" inside it (name matched ignoring capitals).
  Android refuses the very top of a drive, so she creates/opens a folder in the picker. "Save a copy somewhere else" = Android's Save screen.
  The choice ("folder" or "default") is in SharedPreferences "backup-location", not data.json. Folder choice tested on real Google Drive on the S21 FE.
- Home banners, top of the screen, in this order and colours: (1) `PermissionAlertBanner` light red: one button per missing permission (install unknown
  apps [only when the update repo is set], notifications); (2) `SettingsAlertBanner` light orange: backup location not decided yet, says where the default
  goes, "Choose backup location" or "Ignore - keep the default location" (the banner then goes away); (3) the update banner light green
  (`Updater.available`, "Download and install"). Colours are in `BannerColors` (light and dark variants). `PermissionStatus` and `Updater.installAllowed`
  are refreshed in `MainActivity.onResume`.
- Wizard is 7 steps: 1 allow updates + notifications, 2 restore, 3 backup location (choose a folder, or "Keep the default location"), 4 current tenants,
  5 earlier tenants, 6 reminders, 7 Claude key. A restore that brings tenants back goes 3 -> 7.
- Share target: `MainActivity` (launchMode singleTask) accepts SEND / SEND_MULTIPLE for PDF, images and octet-stream. `share/Incoming.kt`
  copies the content URI at once (Gmail's grant is short-lived) and sniffs the real type from the file's first bytes.
  `ui/ImportScreen.kt` asks "invoice or meter photo?" and "which month?".
- Automatic reading (`reading/`): `ReaderService` runs `ClaudeReader` (official anthropic-java SDK, model claude-opus-5-5, structured JSON
  output, effort MEDIUM) in the background when a file is attached and a Claude key is saved (Settings; `data/ApiKeyStore.kt`, Keystore
  AES-GCM, never in data.json or backups). The result goes through engine `InvoiceReading`/`MeterReading` (`Reading.kt`): fills ONLY
  empty boxes, and runs checks (lines vs invoice total, account number, statement month vs draft month with a "move draft" suggestion,
  meter serial, reading vs last month). Numbers filled by the reader must be confirmed by the person before "Review & send" unlocks.
  Debug builds only: canned answers pushed to `files/fake-invoice.json` / `fake-meter.json` act as the reader (no key needed).
  The REAL call with a valid key has not been exercised end to end: only the SDK/network/401 path (with a fake key) and the UI flow.
  Refusal `fallbacks` (server-side beta) are not enabled; a refusal just falls back to typing.
- Backup/restore (`data/Backup.kt`): zip of data.json + attachments, via the system file picker; the key is excluded. Restore sets the
  old data aside in `files/before-restore/`.
- Reminders (`reminders/`): daily WorkManager check; rules in engine `Reminders.kt` (from the 25th, waiting months, at most every other day).
- Updates (`update/Updater.kt`): checks `https://api.github.com/repos/<wewrc.updateRepo>/releases/latest` (at most daily at start, or the
  Settings button), downloads the APK, verifies the digest when given, then Android's installer asks the person to confirm
  (needs "install unknown apps" for the app; Play Protect may ask to scan). Release signing key lives OUTSIDE the project in
  `C:\Users\zaina\WaterSplit-signing\`; see `docs/RELEASING.md` and `.github/workflows/release.yml`.
- Phases: 1 engine + history replay, 2 app (drafts, share target, image statements, WhatsApp, tenants), 3 invoice reading, 4 meter photo
  reading, 5 backup, reminders, GitHub releases + self-update: ALL BUILT. Still to verify by the owner: a real Claude key reading real
  files, picking a real contact, direct-to-chat WhatsApp send, the GitHub workflow itself, and a first release-signed install.

## Build and test (Windows)
```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"   # JDK 17; machine default is Java 24, too new for Android builds
.\gradlew.bat :engine:test :app:assembleDebug --console=plain
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "C:\Users\zaina\AppData\Local\wewrc-build\app\outputs\apk\debug\app-debug.apk"
```
- Build output lives OUTSIDE OneDrive: `~/.gradle/gradle.properties` has `wewrc.buildRoot=C:/Users/zaina/AppData/Local/wewrc-build`
  (see root `build.gradle.kts`). OneDrive file locks otherwise break Gradle. Without that property, output goes to `build/` as usual.
- `compileSdk = 37` (current AndroidX needs it); `targetSdk = 36`, `minSdk = 26`. AGP 9.5.1 (built-in Kotlin), Kotlin 2.4.21, Compose BOM 2026.09.00.
- adb screenshots: `adb shell screencap -p /sdcard/x.png` then `adb pull` (piping `exec-out` through PowerShell corrupts the PNG).
- Android SDK: `C:\Users\zaina\AppData\Local\Android\Sdk` (Platform 36, Build-Tools 36.0.0). Use `android --sdk=<path> sdk install ...`
  (the old `sdkmanager.bat` splits `pkg;name` arguments at the semicolon).
- Test phone over USB: Galaxy S21 FE (SM-G990E), Android 16, serial `R5CRC4AB27Y`;
  `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. The target user's phone is a Galaxy A55 (Android 16, One UI 8.5).
- Project is inside OneDrive; if Gradle gets slow or files lock, move `build/` and `.gradle/` out of the synced folder.
- Testing the share target without Gmail: `adb push x.pdf /sdcard/Download/`, broadcast `MEDIA_SCANNER_SCAN_FILE`, find the id with
  `adb shell "content query --uri content://media/external/file --projection _id:_display_name"`, then
  `adb shell am start -n za.westend.watersplit/.MainActivity -a android.intent.action.SEND -t application/pdf -d content://media/external/file/<id> --eu android.intent.extra.STREAM content://media/external/file/<id> --grant-read-uri-permission`
  (the `-d` makes the grant apply). Delete the test files afterwards and `pm clear` the app.
- Testing the updater without GitHub: build two APKs (`-PappVersionName=0.2.0 -PappVersionCode=2 -Pwewrc.updateRepo=local/test`), serve a fake
  `/repos/local/test/releases/latest` JSON + the APK from a local Python server, `adb reverse tcp:8765 tcp:8765`, and put
  `http://127.0.0.1:8765` in the app's `files/update-base.txt` (debug builds only; cleartext to localhost is allowed in `src/debug`).
  Grant "install unknown apps" with `adb shell appops set za.westend.watersplit REQUEST_INSTALL_PACKAGES allow` and reset it after.
  Play Protect then asks "Scan app" (uploads the APK to Google): do not accept that during tests.
- NEVER screenshot the contact picker or any WhatsApp screen (they show the owner's private contacts/chats): check `dumpsys window`
  focus names instead. The owner's real data lives on the test phone: back up `files/data.json` (`run-as ... base64`) before changing
  its format, test on throwaway drafts/tenants, and delete them afterwards; do not `pm clear` without asking.
- NEVER trigger "Send on WhatsApp" in tests with a number that is not the owner's own: WhatsApp can open the chat/preview without a
  tap, and its screens show the owner's private contacts. Use "Preview the statement" to check the image instead.

## Rules for changes
- `engine/src/test/resources/sheet-history.csv` is the golden record of the old sheet (kept locally, never committed: the repo is
  public). Never edit it or loosen `SheetReplayTest` to make a change pass; version the rules instead (`Rules.SHEET_V1` must keep passing).
- The three statements must always add up exactly to the municipal total (current rules).
- No secrets in the repo. The Claude API key is entered on the phone and stored encrypted; releases are public APKs.
- Keep the release signing key backed up outside the repo; losing it breaks in-place updates.
