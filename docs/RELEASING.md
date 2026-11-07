# Publishing a new version

The app updates itself from this repository's GitHub releases. A release is built automatically (`.github/workflows/release.yml`)
whenever you push a tag that starts with `v`.

## One-time setup

1. **Create the repository on GitHub** and push this project to it. It can stay **private**: the app then logs in with a read-only
   token (step 2, `UPDATE_TOKEN`). The APK contains the old sheet's rent history, so a public repository would publish that too.
   The APK holds no secrets: the Claude key is typed into the phone, never into the app or the repository.
2. **Make the update token** (only for a private repository): GitHub -> profile picture -> Settings -> Developer settings ->
   Personal access tokens -> Fine-grained tokens -> Generate new token. Repository access: *Only select repositories* -> WaterSplitter.
   Permissions -> Repository permissions -> **Contents: Read-only** (nothing else). Choose the longest expiry offered. Copy the token
   into a secret called `UPDATE_TOKEN` (step 3). It is built into each release APK, so **when the token expires, phones stop finding
   updates**: make a new token, update the secret, and publish a new release (install that one by hand once).
3. **Add five secrets** (repository -> Settings -> Secrets and variables -> Actions -> New repository secret). The first four values
   are in `C:\Users\zaina\WaterSplit-signing\KEEP-THIS-SAFE.txt` (outside the project, outside OneDrive):
   - `UPDATE_TOKEN` - the token from step 2 (leave it out for a public repository)
   - `KEYSTORE_BASE64` - the whole contents of `keystore.base64.txt` from that folder
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS` (`watersplit`)
   - `KEY_PASSWORD`
4. **Back up `C:\Users\zaina\WaterSplit-signing\`** somewhere else too (USB stick, password manager). If the signing key is lost,
   phones that have the app can never be updated in place.

## Each release

```powershell
git tag v0.3.0
git push origin v0.3.0
```

GitHub runs the tests, builds and signs `water-split-0.3.0.apk` and attaches it to a new release. Phones check for it when the app
starts (at most once a day) or when someone taps *Settings -> App updates -> Check for updates*, then download it and hand it to
Android's installer.

Version numbers only need to go up (`v0.3.0` -> `v0.4.0`). The build number is taken from the workflow run number automatically.

## First install on a phone

- Install the **release** APK from the GitHub release (open the release page on the phone and download the APK).
- Android asks to allow installing from your browser/Files app once.
- Google Play Protect may say *"App scan recommended"*. Tapping *Scan app* sends the APK to Google for a check (the APK contains the
  old spreadsheet's rent history). It is only asked for apps Google hasn't seen; updates signed with the same key are usually not asked again.
- **A phone that has a debug build installed must uninstall it first** (a different signing key), which deletes its data:
  use *Settings -> Backup -> Save a backup* first, and *Restore from a backup* afterwards.

## Local release build (rarely needed)

```powershell
$env:WEWRC_KEYSTORE_FILE = "C:\Users\zaina\WaterSplit-signing\release.jks"
$env:WEWRC_KEYSTORE_PASSWORD = "<store password>"; $env:WEWRC_KEY_ALIAS = "watersplit"; $env:WEWRC_KEY_PASSWORD = "<same password>"
.\gradlew.bat :app:assembleRelease "-PappVersionName=0.3.0" "-PappVersionCode=3" "-Pwewrc.updateRepo=<owner>/<repo>"
```

Without the `WEWRC_*` variables the release build is unsigned and will not install. Without `wewrc.updateRepo` the app has no
update check ("Updates aren't set up for this build yet").
