# Water Splitter

**Photograph the water meter, share the municipal invoice, and let Claude do the reading.** Water Splitter is an
Android app for a landlord whose property has two houses on one municipal water, sewerage and refuse account. It reads
the invoice and the meter photo with Claude AI, splits the bill fairly between the **outbuilding tenant**, the
**forebuilding tenant** and the **owner**, draws an itemised statement for each, and sends it on WhatsApp.

It is built for someone who is not technical: few buttons, plain words, and a person always confirms the numbers
before anything is sent.

<p align="center">
  <img src="docs/screenshots/home.png" width="190" alt="Home">
  <img src="docs/screenshots/invoice-read.png" width="190" alt="Invoice read by Claude">
  <img src="docs/screenshots/meter-read.png" width="190" alt="Meter photo read by Claude">
  <img src="docs/screenshots/statement.png" width="190" alt="Statement">
</p>

> All names, numbers, addresses and documents in the screenshots are made up. The invoice is a sample, not a real
> municipal invoice, and the meter is a drawing.

## Claude reads the invoice and the meter

This is the heart of the app. With a Claude API key saved on the phone, attaching a file starts the reading in the
background:

- **The municipal invoice (PDF).** Claude reads the property's water use in kL, every charge (water, water
  availability, sewerage, sewerage availability, refuse, general rates, interest, balance brought forward, payments)
  and the total payable.
- **The outbuilding meter photo.** Claude reads the odometer-style dial: black digits are whole kilolitres, the red
  wheels are the decimals. It reports its confidence, the meter's serial number, and whether the last wheel was
  between two digits.

Claude is only a helper. The app fills **only empty boxes**, shows what it read next to the original document, and
asks the person to press **"These numbers match the invoice"** or **"This reading matches the photo"** before
"Review & send" unlocks. It then double-checks the work and raises a red warning for anything suspicious:

- the invoice lines do not add up to the invoice's own total payable (a number may have been misread);
- the invoice is for a different **municipal account** than the one in Service Settings;
- the invoice is dated for a different **month** than the draft, with a one-tap "Change this draft to October 2026";
- the photo shows a different **meter serial number**;
- the new reading is **lower than last month's** (meter replaced, or a misread?);
- the photo is hard to read, or the last wheel was uncertain.

Without a key the app works exactly the same, and the numbers are typed in ("Type the numbers yourself").

<table>
<tr>
<td align="center"><img src="docs/screenshots/invoice-read.png" width="230"><br><sub>Invoice read, lines checked against the total</sub></td>
<td align="center"><img src="docs/screenshots/meter-read.png" width="230"><br><sub>Meter photo read, usage worked out from last month</sub></td>
<td align="center"><img src="docs/screenshots/warnings-1.png" width="230"><br><sub>Wrong account and wrong month are flagged</sub></td>
<td align="center"><img src="docs/screenshots/warnings-2.png" width="230"><br><sub>Lines that do not add up to the total</sub></td>
</tr>
</table>

Technical notes: the reader uses the official Anthropic Java SDK with structured JSON output
(model `claude-opus-5-5`). The key is typed in on the phone, stored encrypted with the Android Keystore, and is never
written to the app's data or to backups. The files are sent to Anthropic only when a key is saved. The screenshots
here were taken with a built-in test reader that answers for the sample documents; the same screens appear when
Claude answers.

## A month, step by step

1. **New month.** Start a month; several can be in progress at once.
2. **Bring in the invoice and the meter photo**, either by *sharing* them to the app (from Gmail or WhatsApp) or by
   *attaching* them. The attach screens list every PDF or photo found in Downloads, Documents, DCIM and Pictures, newest
   first, with a preview and the date and time. The share screen asks "invoice or meter photo?" and "which month?".
3. **Confirm what was read** (see above).
4. **Review & send.** The three statements are shown with every line. The app offers to delete the original files from
   the phone (it keeps its own copy).
5. **Send on WhatsApp.** Each statement is drawn as a picture, so it shows inline in the chat. The tenant's number is
   remembered, or picked from the contacts.

<table>
<tr>
<td align="center"><img src="docs/screenshots/new-month.png" width="170"><br><sub>New month</sub></td>
<td align="center"><img src="docs/screenshots/pick-invoice.png" width="170"><br><sub>Pick the invoice</sub></td>
<td align="center"><img src="docs/screenshots/pick-meter-photo.png" width="170"><br><sub>Pick the meter photo</sub></td>
<td align="center"><img src="docs/screenshots/share-target.png" width="170"><br><sub>Shared from another app</sub></td>
<td align="center"><img src="docs/screenshots/delete-originals.png" width="170"><br><sub>Tidy up the originals</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/review.png" width="170"><br><sub>Review & send</sub></td>
<td align="center"><img src="docs/screenshots/statement.png" width="170"><br><sub>The statement sent on WhatsApp</sub></td>
<td align="center"><img src="docs/screenshots/month.png" width="170"><br><sub>A finished month</sub></td>
<td align="center"><img src="docs/screenshots/home.png" width="170"><br><sub>Home</sub></td>
<td align="center"><img src="docs/screenshots/help.png" width="170"><br><sub>Built-in how-to</sub></td>
</tr>
</table>

## How the bill is split

Water and sewerage follow the outbuilding's own meter; the forebuilding pays the rest. Availability charges and refuse
are shared half and half by the days each house was occupied (empty days go to the owner). General rates, interest and
adjustments go to the owner unless assigned to a house. VAT is recalculated line by line, every printed line is
rounded to cents, and any leftover cents go to the owner, so **the three statements always add up exactly to the
municipal total**. The full rules are in [docs/RULES.md](docs/RULES.md); the calculation engine is a pure Kotlin
library with unit tests, using exact decimal arithmetic.

## Tenants and history

Tenants are recorded per house for a run of whole months. Each month's statements go to whoever lived there that
month, and old months keep the right name when someone moves out. Overlaps are refused, and adding a new tenant closes
the previous stay automatically.

<p>
  <img src="docs/screenshots/tenant-settings.png" width="200" alt="Tenant Settings">
  <img src="docs/screenshots/service-settings.png" width="200" alt="Service Settings">
  <img src="docs/screenshots/settings.png" width="200" alt="Settings">
</p>

## Moving months between phones, and backups

- **Share months.** Long-press any month (finished or still in progress) to select it, tick more, then **Share**,
  **Save** or **Delete** from the floating frosted-glass menu. The months travel as one `.wsmonths` file.
- **Import.** Tapping a `.wsmonths` file anywhere on the phone, or **Import months from a file** on the home screen,
  opens the app. It lists every month in the file; new months have a tick box, months already in the app get **Skip** or
  **Replace mine**, with **Skip all** and **Replace all** for speed.
- **Backups.** A whole-app `.wsbackup` file is refreshed automatically after every finished month, to the folder of
  your choice. Tapping one anywhere on the phone, or **Restore a backup** in settings, starts a restore. The Claude key
  is never in a backup.

<table>
<tr>
<td align="center"><img src="docs/screenshots/select-light.png" width="170"><br><sub>Select months</sub></td>
<td align="center"><img src="docs/screenshots/select-dark.png" width="170"><br><sub>The same in dark mode</sub></td>
<td align="center"><img src="docs/screenshots/import-picker.png" width="170"><br><sub>Find a months file</sub></td>
<td align="center"><img src="docs/screenshots/import-choose.png" width="170"><br><sub>Choose what to import</sub></td>
<td align="center"><img src="docs/screenshots/app-settings-backup.png" width="170"><br><sub>Backups and reminders</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/restore-picker.png" width="170"><br><sub>Find a backup</sub></td>
<td align="center"><img src="docs/screenshots/restore-dialog.png" width="170"><br><sub>Restore it</sub></td>
<td align="center"><img src="docs/screenshots/app-settings-claude.png" width="170"><br><sub>Claude key and reminders</sub></td>
<td></td><td></td>
</tr>
</table>

## Reminders and updates

A daily check reminds the landlord from a chosen day of the month (default the 25th) while a month is still waiting for
its invoice, meter photo or checking, at most every other day. The app updates itself from GitHub Releases: it
downloads the new APK, checks its digest, and Android's installer asks for the final confirmation.

## First-time setup

A six-step wizard asks for the three permissions (installing updates, notifications, access to files), offers to
restore a backup, takes the current tenants (name and WhatsApp number, or "nobody lives here"), earlier tenants,
the reminder day and the Claude key. Every step can be revisited later in Settings. A restored backup skips nothing: it
fills in the steps so they can be changed.

<table>
<tr>
<td align="center"><img src="docs/screenshots/setup-1-permissions.png" width="150"><br><sub>1. Permissions</sub></td>
<td align="center"><img src="docs/screenshots/setup-2-restore.png" width="150"><br><sub>2. Restore</sub></td>
<td align="center"><img src="docs/screenshots/setup-3-tenants.png" width="150"><br><sub>3. Tenants</sub></td>
<td align="center"><img src="docs/screenshots/setup-4-earlier-tenants.png" width="150"><br><sub>4. Earlier tenants</sub></td>
<td align="center"><img src="docs/screenshots/setup-5-reminders.png" width="150"><br><sub>5. Reminders</sub></td>
<td align="center"><img src="docs/screenshots/setup-6-claude-key.png" width="150"><br><sub>6. Claude key</sub></td>
</tr>
</table>

## Getting the app

Download the newest APK from the [Releases](https://github.com/ZainAjam/WaterSplitter/releases) page and open it on the
phone (Android asks once to allow installing from that source). Later versions are offered inside the app. It needs
Android 8 or newer.

The repository is public, so it holds no rent history, account number, meter serial or address. Those are entered on the
phone or arrive through a backup.

## Project layout

| Folder | What is in it |
|---|---|
| `engine/` | Pure Kotlin/JVM calculation and checking library (BigDecimal only), fully unit tested |
| `app/` | The Android app (Kotlin, Jetpack Compose); no database, one JSON file in private storage |
| `docs/` | [Business rules](docs/RULES.md) and [release process](docs/RELEASING.md) |

## Building

Needs JDK 17 and the Android SDK (compileSdk 37, minSdk 26).

```
./gradlew :engine:test :app:assembleDebug
```

Releases are built and signed by GitHub Actions when a `v*` tag is pushed (see [docs/RELEASING.md](docs/RELEASING.md)).
