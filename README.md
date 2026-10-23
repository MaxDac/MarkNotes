# MarkNotes

Simple Android markdown notes app with folders and sync to Proton Drive (unofficial), Dropbox and OneDrive.

> **This is a third-party application not officially supported by Proton.**
> MarkNotes is a personal, non-commercial project. It is not affiliated with or endorsed by Proton AG,
> and it does not use any Proton logos or branding.

## Features

- **Offline-first notes**: every note is a `.md` file in a folder tree inside the app's private storage
  (`filesDir/notes`). Folders map to real directories.
- **Folder system**: create, open, rename, delete and **move** notes and folders (item menu → "Move to…").
  A navigation drawer shows the whole folder tree so you can jump straight to any folder.
- **Editor**: raw markdown editing with a toggle to a rendered preview (Markwon: tables, strikethrough,
  task lists, syntax-highlighted fenced code blocks via Prism4j). A formatting toolbar above the keyboard
  inserts bold, italic, strikethrough, headings, bullet/numbered lists, checkboxes, quotes, inline code,
  code blocks and links. Pressing Enter on a list, checkbox or quote line continues it on the next line;
  Enter on an empty item ends the list. Changes are autosaved (debounced, and flushed when leaving the editor).
- **Note links with `@`**: every note has a stable 8-character ID stored in a small front-matter header at the
  top of the file (hidden in the editor and preview). Typing `@` at the start of a line or after a space opens a
  panel listing every note with its full folder path and ID (with a copy button); keep typing to filter by ID
  or by title/path, and tap a note to insert `[Title](marknotes:<id>)`. In the preview, tapping the link opens
  the note; links to missing notes are shown struck through and tapping them says "Note not found".
- **Images**: the toolbar's image button adds a photo from the gallery (photo picker) or the camera. Images are
  downscaled to at most 2048 px, stored once under `attachments/` (named by content hash), shown in the preview
  and synced to every drive. Settings → **Clean unused images** deletes images no note refers to.
- **Import from other apps**: pick MarkNotes in Android's share sheet to turn shared text, web pages (HTML),
  `.txt`/`.md`/`.html` files, Word documents (`.docx`), PDFs (text only) or images into a new note. The import
  screen shows a preview and lets you set the title and folder. **Auto-format as Markdown** (on by default)
  converts headings, lists, emphasis, links, tables and code; turn it off to keep the text as it is. Shared
  images and images inside `.docx` files are stored as attachments; web images stay links. Conversion runs on
  the device.
- **Chord sequences** (guitar and bass, ported from [ChordBuilder](https://github.com/MaxDac/ChordBuilder)): the
  browser's **New chord sequence** action creates a note with `type: chords` in its front-matter, shown with a
  music icon. It opens in a chord editor instead of the markdown editor: an ordered list of chords with small
  diagrams (add, edit, duplicate, move, delete) and a builder with a horizontally scrollable fretboard (guitar
  frets 1–24; bass frets 9–24 with a red line after the 12th), finger selection, tags and a note. Each chord
  needs at least three non-muted strings; chords with the same name and instrument are numbered (`1 C`, `2 C`).
  **View as text** shows the raw, human-readable file (`## C (guitar)` / `frets: x 3/3 2/2 0 1/1 0`). A file
  whose body can't be read as chords (for example after a sync conflict) opens in the markdown editor with
  the error and its line number.
- **Appearance** (Settings): theme **System** (default), **Light** or **Dark**, and an accent colour chosen
  from 8 palettes (Blue, Teal, Green, Lime, Amber, Orange, Red, Purple) or **Dynamic** (Material You,
  Android 12+, the default where available).
- **Multi-drive two-way sync**: connect any number of **Proton Drive**, **Dropbox** and **OneDrive** accounts
  (Settings → Add drive). Every drive mirrors the same local note tree:
  - for Dropbox and OneDrive you browse to a folder and can optionally create a root folder inside it
    (default `MarkNotes`); for Proton Drive you pick a folder name in *My files*;
  - sync runs on app launch and when you tap **Sync now**; there is no background or periodic polling;
  - only folders, `.md` files and images under `attachments/` are synced (images are never merged: they are
    immutable and named by their content hash). Each drive keeps its own sync state, plus a small manifest at
    `.marknotes/manifest.json` on the drive (content hashes and the device that last wrote each note);
  - when only one side changed, that version wins. Deletions and moves propagate (a move is a delete
    plus a create);
  - edits made on both sides are merged line by line (a three-way merge against the last synced version).
    When edits overlap, the newest version (by modification time; ties go to the drive) is kept and the other
    one is saved next to it as `name (conflicted copy).md` with a new note ID, on every drive. The **Sync
    conflicts** screen merges a copy back: for each conflicting change you pick **Kept**, **Copy**, **Both** or
    **Neither**, or you edit the merged text by hand (with git-style `<<<<<<<` markers). You can also discard
    the copy, or keep both files;
  - the sync algorithm (planning, merge, conflicted copies, manifest) is the shared Rust core
    [marknotes-sync](https://github.com/MaxDac/marknotes-sync), also used by the desktop and web apps.
- **Proton login**: Proton SRP password login with TOTP two-factor authentication. CAPTCHA / human
  verification is not supported: you get a clear error instead.
- **Dropbox / OneDrive login**: the official Dropbox SDK (OAuth 2 PKCE) and Microsoft MSAL.
- Sessions and tokens are stored encrypted with an Android Keystore AES-GCM key. Disconnecting a drive
  signs you out, and revokes the session where the provider supports it.

The on-disk note format (IDs, note links, images, chord sequences) is shared with the desktop and web apps and described in
[marknotes-spec](https://github.com/MaxDac/marknotes-spec/blob/main/note-format.md), checked out at `spec/` as a git
submodule (`git submodule update --init`).

Out of scope: search, tags, background sync, widgets, local encryption.

## Project layout

| Module | What it contains |
| --- | --- |
| `:core` | Pure Kotlin/JVM: local note store, sync engine (single and multi-drive) executing the plans of the shared [marknotes-sync](https://github.com/MaxDac/marknotes-sync) core, `RemoteDriveStore` interface, OneDrive (Microsoft Graph) store, Proton SRP + auth client. Fully unit tested. |
| `:app` | Android app: Jetpack Compose + Material 3, Navigation Compose, MVVM with `StateFlow`, Room (drive accounts and sync metadata), manual DI, Dropbox store, Dropbox/MSAL sign-in. |
| `:proton-drive` | Proton Drive store built on the [Proton Drive SDK](https://github.com/ProtonDriveApps/sdk), plus the protoncore network stack and address-key resolvers it needs. Always included. |

The sync engine only talks to the `RemoteDriveStore` interface, so SDK changes stay contained in
`:proton-drive`. Tests use an in-memory `FakeRemoteDriveStore`.

## Build

Requirements: JDK 21+ (Android Studio's bundled JBR works) and the Android SDK with platform 37
(compileSdk 37, targetSdk 36). The app runs on Android 10+ (minSdk 29, required by the Proton Drive SDK).
The Gradle wrapper (9.8.1, AGP 9.4 with built-in Kotlin 2.4) is included:

```sh
./gradlew assembleDebug                      # build the debug APK
./gradlew ktlintCheck detekt lintDebug       # ktlint, detekt and Android Lint (ktlintFormat auto-fixes style)
./gradlew testDebugUnitTest :core:test       # unit tests (:core is a plain JVM module)
./gradlew :core:parityTest                   # marknotes-spec fixtures (git submodule update --init first)
./gradlew :app:connectedDebugAndroidTest     # Compose UI tests on a running emulator/device
```

CI mirrors these commands. The checks live in the reusable `.github/workflows/checks.yml`: four required
checks, "Lint (ktlint, detekt, Android Lint)", "Unit tests", "Instrumented tests (emulator)" and
"Build debug APK". The debug APK always includes the Proton Drive SDK. A fifth check, "Parity (marknotes-spec
fixtures)", checks out the `spec/` submodule and runs every [marknotes-spec](https://github.com/MaxDac/marknotes-spec)
golden fixture against the real implementation (`./gradlew :core:parityTest` and the `:app` mention test;
see AGENTS.md, "Cross-app parity").
`pr-ci.yml` runs them on every pull request, and `main-ci.yml` reruns them on `main` and builds an unsigned
release APK. All jobs run on `ubuntu-24.04`, like the desktop and web repos. Pull requests also run
"Dependency review" (fails on high-severity vulnerabilities in added dependencies and comments a summary on
the PR when it fails) and "actionlint" (workflow linting). GitHub can't resolve Gradle version catalogs or
transitive dependencies on its own, so `dependency-submission.yml` submits the resolved Gradle dependency
graph on every push to `main` (feeding Dependabot alerts), and "Dependency review" submits the PR head's graph
before comparing (except for fork PRs). `codeql.yml` runs CodeQL for Kotlin/Java and GitHub
Actions on pull requests, on `main` and weekly. `dependabot-automerge.yml` enables squash auto-merge for
Dependabot patch and minor updates (never majors or `me.proton.core`), so they merge once the checks pass.

### Releasing

Releases are published only as signed APKs on GitHub Releases.

**Repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | The release keystore, base64-encoded (`base64 -w0 release.keystore`) |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Alias of the signing key |
| `RELEASE_KEY_PASSWORD` | Key password |
| `DROPBOX_APP_KEY` | Dropbox app key |
| `MSAL_CLIENT_ID` | Azure application (client) ID |
| `MSAL_RELEASE_SIGNATURE_HASH` | Base64 SHA-1 of the release signing certificate (see below) |

Compute `MSAL_RELEASE_SIGNATURE_HASH` from the release keystore:

```sh
keytool -exportcert -alias <alias> -keystore release.keystore | openssl sha1 -binary | openssl base64
```

**Optional repository variable** `RELEASE_CERT_SHA256`: the SHA-256 digest of the release signing
certificate (hex, colons optional). When set, the release fails if the built APK is signed by any other
certificate. Get it with `keytool -list -v -keystore release.keystore -alias <alias>` (the `SHA256:` line) or
from the "Signer cert SHA-256" row of a previous release's job summary.

**Cutting a release**: Actions → Release → Run workflow, with a `version` (`X.Y.Z`) and the `prerelease`
flag. The workflow:

1. validates the version (tag `vX.Y.Z` must not exist; `versionCode` = `X*10000 + Y*100 + Z`);
2. runs the same checks as PR CI;
3. fails if `DROPBOX_APP_KEY`, `MSAL_CLIENT_ID` or `MSAL_RELEASE_SIGNATURE_HASH` is empty, so a release never
   silently ships with a provider "not configured" (tick `allow-missing-providers` to downgrade this to a
   warning);
4. builds and signs the APK, checks it with `apksigner verify` and, if `RELEASE_CERT_SHA256` is set, pins
   the signer certificate;
5. uploads `marknotes-X.Y.Z.apk` and a `SHA256SUMS` file (the same name the desktop and web releases use;
   check with `sha256sum -c SHA256SUMS`) as a workflow artifact and publishes them in the `vX.Y.Z` GitHub
   Release with generated notes;
6. writes a job summary with the version, `versionCode`, signer digest and provider status.

**Rollback**: Android refuses to install a lower `versionCode` over a higher one, so roll forward. Delete
or mark the bad release as a pre-release (`gh release delete vX.Y.Z --cleanup-tag`, or
`gh release edit vX.Y.Z --prerelease`) so it is no longer "Latest", then release a fix with a higher patch
version (for example a revert on `main` released as `X.Y.Z+1`). Users who already installed the bad build
must update to the new one; downgrading requires uninstalling the app, which deletes its local data.

Locally, `assembleRelease` signs only when `MARKNOTES_KEYSTORE_PATH` (plus the matching password/alias env
vars) is set; otherwise it stays unsigned.

### Configuring Dropbox and OneDrive

Both providers need app registrations. Without them the build still works, and "Add drive" shows the
provider as not configured. See [docs/cloud-providers.md](docs/cloud-providers.md) for the step-by-step
setup. Put the values in `~/.gradle/gradle.properties` (or pass them with `-P`):

```properties
marknotes.dropboxAppKey=<Dropbox app key>
marknotes.msalClientId=<Azure application (client) ID>
marknotes.msalSignatureHash=<base64 SHA-1 of the signing certificate>
```

- **Dropbox**: create an app in the [Dropbox App Console](https://www.dropbox.com/developers/apps) with
  "Full Dropbox" access and the scopes `account_info.read`, `files.metadata.read`, `files.content.read` and
  `files.content.write`. PKCE needs no app secret. The SDK's `AuthActivity` handles the redirect
  (scheme `db-<app key>`).
- **OneDrive**: register an app in [Microsoft Entra](https://entra.microsoft.com) for personal Microsoft
  accounts and organizational accounts, with the delegated permissions `Files.ReadWrite` and `User.Read`.
  Add an **Android** platform with package `io.github.maxdac.marknotes` and the signature hash. Azure then
  shows the redirect URI `msauth://io.github.maxdac.marknotes/<URL-encoded hash>`, which is the one
  MarkNotes uses. To get the debug hash, run
  `keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore | openssl sha1 -binary | openssl base64`.
  On Windows without openssl, copy the `SHA1` line printed by `.\gradlew :app:signingReport` and convert it
  in PowerShell:
  `[Convert]::ToBase64String([byte[]]('AA:BB:...' -split ':' | ForEach-Object { [Convert]::ToByte($_, 16) }))`.
  The hash depends on the signing key, so an APK signed with a different key (another PC's debug key, or
  the release key) fails to sign in to OneDrive. Add one Android platform entry per key in Entra.
- **Release builds**: `release.yml` reads the repository secrets `DROPBOX_APP_KEY`, `MSAL_CLIENT_ID` and
  `MSAL_RELEASE_SIGNATURE_HASH` (the hash of the release keystore). The release fails if any of them is
  missing, unless it is run with `allow-missing-providers`, in which case the provider shows as not configured.

### Proton Drive SDK

`:proton-drive` is always part of the build. It depends on `me.proton.drive:sdk` from Maven Central and
on the protoncore libraries it is built against (`me.proton.core:network-data`, `crypto-android`, ...;
versions in `gradle/libs.versions.toml`). The SDK's Kotlin bindings wrap the C# SDK through JNI; the
published AAR bundles the NativeAOT library (`libproton_drive_sdk_jni.so`) for `arm64-v8a` and `x86_64`
only, so the APK is limited to those ABIs and Proton Drive is unavailable on 32-bit devices.

The SDK doesn't handle login, so MarkNotes keeps its own (`:core` `ProtonAuthClient`) and wires the
session into the SDK (`ProtonDriveStoreFactory`):

- a protoncore `ApiProvider` sends the SDK's requests with the stored session's UID and access token and
  `x-pm-appversion: external-drive-marknotes@0.1.0-alpha`. On a 401 it refreshes the tokens through
  `ProtonConnector`, which saves them, so the app and the SDK never hold different refresh tokens.
  Human/device verification and missing-scope prompts aren't supported: those requests fail and you
  sign in again;
- `ProtonAddressKeys` implements the SDK's `UserAddressResolver` and `PublicAddressResolver`. It fetches
  the user's addresses and keys (`core/v4/users`, `core/v4/addresses`, `core/v4/keys/all`) and unlocks
  address keys on demand with the key passphrases derived at login (`ProtonSession.keyPassphrases`),
  decrypting the address-key token with the user key when present. Sessions saved before key
  passphrases were stored must sign in again.

The SDK client is kept across syncs and closed when the session or sync folder changes.

Earlier builds left the SDK out on purpose: it was an opt-in module (`-Pmarknotes.protonSdk=true`,
which PowerShell also mangles unless quoted) loaded by reflection, `ProtonDriveStoreFactory` never
created the SDK client because the protoncore `ApiProvider` and address-key resolvers were missing, and
the CI job building it was non-blocking.
## Proton API usage rules

MarkNotes follows Proton's rules for third-party personal projects:

- every request sends `x-pm-appversion: external-drive-marknotes@0.1.0-alpha`;
- only official Proton endpoints are used (`https://drive-api.proton.me`);
- sync is event-based: the remote tree is traversed once, then kept up to date with the Drive event
  stream. Sync only runs on app launch and on demand, with no frequent recursive polling;
- no Proton logos or branding;
- the login screen states: "This is a third-party application not officially supported by Proton."

## Security notes

- The password is only used to compute the SRP proof and key passphrases. It is never stored.
- The SRP modulus is extracted from Proton's PGP-signed message, but **the signature isn't verified
  yet**. That is acceptable for a personal project, but it should be added before wider use.
- Accounts with a separate mailbox password (two-password mode) and FIDO2-only 2FA are not supported in v1.

## License

MarkNotes is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE.md).
You may use, copy, fork, and modify the code for any noncommercial purpose (personal use, study,
research, hobby projects, and use by charities, educational or public institutions). Any
commercial use is not permitted.

Note: this is not an OSI-approved open source license, since it restricts commercial use.

## Licensing notes

The license above covers this project's own code. Third-party dependencies remain under their own
licenses.

The Proton Drive SDK and protoncore libraries are GPLv3, and every build includes them, so builds must
respect the GPLv3. The
SRP implementation in `:core` was written for this project, following the public protocol as
implemented in Proton's MIT-licensed [go-srp](https://github.com/ProtonMail/go-srp). No
protoncore_android code was copied; it is used as a library dependency.

Importing uses [jsoup](https://jsoup.org) (MIT) for HTML and
[PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0) for PDF text.
