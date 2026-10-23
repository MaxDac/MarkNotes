# MarkNotes

Simple Android markdown notes app with folders, offline-first, and optional two-way sync with Dropbox.

This is the libre build of MarkNotes, published on [F-Droid](https://f-droid.org/packages/io.github.maxdac.marknotes.libre/)
and on [GitHub Releases](https://github.com/MaxDac/marknotes-android/releases): free software only, built from source,
reproducible. [MarkNotes Pro](https://github.com/MaxDac/MarkNotes) is the same app (it includes this repo as a git
submodule) with OneDrive and Proton Drive sync added, released on GitHub only. The two install side by side
(`io.github.maxdac.marknotes.libre` and `io.github.maxdac.marknotes`).

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
  and synced. Settings → **Clean unused images** deletes images no note refers to.
- **Import from other apps**: pick MarkNotes in Android's share sheet to turn shared text, web pages (HTML),
  `.txt`/`.md`/`.html` files, Word documents (`.docx`), PDFs (text only) or images into a new note, optionally
  converted to Markdown. Conversion runs on the device.
- **Chord sequences** (guitar and bass, ported from [ChordBuilder](https://github.com/MaxDac/ChordBuilder)): notes
  with `type: chords` open in a chord editor with a fretboard builder and chord diagrams.
- **Appearance** (Settings): theme **System** (default), **Light** or **Dark**, and an accent colour chosen
  from 8 palettes or **Dynamic** (Material You, Android 12+).
- **Dropbox two-way sync** (optional): connect Dropbox accounts (Settings → Add drive), browse to a folder and
  optionally create a root folder inside it (default `MarkNotes`):
  - sync runs on app launch and when you tap **Sync now**; there is no background or periodic polling;
  - only folders, `.md` files and images under `attachments/` are synced, plus a small manifest at
    `.marknotes/manifest.json` on the drive;
  - edits made on both sides are merged line by line (a three-way merge against the last synced version).
    When edits overlap, the newest version is kept and the other is saved next to it as
    `name (conflicted copy).md`; the **Sync conflicts** screen merges a copy back;
  - the sync algorithm is the shared Rust core [marknotes-sync](https://github.com/MaxDac/marknotes-sync), also
    used by the desktop and web apps. Dropbox sign-in uses the official Dropbox SDK (OAuth 2 PKCE); tokens are
    stored encrypted with an Android Keystore AES-GCM key.

Dropbox is a proprietary network service, so F-Droid marks the app with the *NonFreeNet* anti-feature. Without a
Dropbox account the app is fully local.

The on-disk note format (IDs, note links, images, chord sequences) is shared with the desktop and web apps and
described in [marknotes-spec](https://github.com/MaxDac/marknotes-spec/blob/main/note-format.md), checked out at
`spec/` as a git submodule (`git submodule update --init`).

## Project layout

| Module | What it contains |
| --- | --- |
| `:core` | Pure Kotlin/JVM: local note store, sync engine (single and multi-drive) executing the plans of the shared [marknotes-sync](https://github.com/MaxDac/marknotes-sync) core, `RemoteDriveStore` interface. Fully unit tested. |
| `:app-shared` | Android library with the whole app: Jetpack Compose + Material 3, Navigation Compose, MVVM with `StateFlow`, Room, manual DI, the Dropbox store and sign-in. Providers plug in through `DriveConnector`; MarkNotes Pro adds OneDrive and Proton Drive this way. |
| `:app` | The libre application (`io.github.maxdac.marknotes.libre`): `LibreApp` with the Dropbox connector. |

## Build

Requirements: JDK 21+ (Android Studio's bundled JBR works) and the Android SDK with platform 37
(compileSdk 37, targetSdk 36, minSdk 29). The Gradle wrapper (9.8.1, AGP 9.4 with built-in Kotlin 2.4) is included:

```sh
./gradlew assembleDebug                      # build the debug APK
./gradlew ktlintCheck detekt lintDebug       # ktlint, detekt and Android Lint (ktlintFormat auto-fixes style)
./gradlew testDebugUnitTest :core:test       # unit tests (:core is a plain JVM module)
./gradlew :core:parityTest                   # marknotes-spec fixtures (git submodule update --init first)
./gradlew :app:connectedDebugAndroidTest     # Compose UI tests on a running emulator/device
```

By default the build downloads marknotes-sync (the Kotlin bindings and the native library) from its
[GitHub releases](https://github.com/MaxDac/marknotes-sync/releases), at the version in `gradle/libs.versions.toml`.

The Dropbox app key is committed in `app/build.gradle.kts` (it is public by design: PKCE, no secret). To use your
own Dropbox app, pass `-Pmarknotes.dropboxAppKey=<key>` or set it in `~/.gradle/gradle.properties`; create the app
in the [Dropbox App Console](https://www.dropbox.com/developers/apps) with "Full Dropbox" access and the scopes
`account_info.read`, `files.metadata.read`, `files.content.read` and `files.content.write`.

### Building from source, like F-Droid

F-Droid doesn't accept prebuilt binaries, so it builds marknotes-sync from source too. On Linux, with rustup, the
Rust toolchain and NDK pinned in `scripts/build-sync-from-source.sh` and cargo-ndk:

```sh
rustup toolchain install 1.99.1 --profile minimal --target aarch64-linux-android,x86_64-linux-android
cargo +1.99.1 install cargo-ndk --version 4.1.2 --locked
sdkmanager "ndk;27.3.13750724"
scripts/build-sync-from-source.sh /tmp/marknotes-sync      # clones the pinned tag if the folder is missing
./gradlew :app:assembleRelease --no-build-cache -Pmarknotes.sync.fromSource=/tmp/marknotes-sync
```

The F-Droid recipe is [`.fdroid.yml`](.fdroid.yml) (with `fdroid/srclibs/marknotes-sync.yml`); see
[docs/fdroid.md](docs/fdroid.md) for how the app is published there.

### CI

The checks live in the reusable `.github/workflows/checks.yml` and are required by the `main` ruleset:
"Lint (ktlint, detekt, Android Lint)", "Unit tests", "Instrumented tests (emulator)", "Build debug APK",
"Parity (marknotes-spec fixtures)" and the two F-Droid checks:

- **F-Droid build** lints the recipe with `fdroid lint` (and checks it is in `fdroid rewritemeta` format and matches
  the app version and the pinned marknotes-sync version), builds marknotes-sync and the release APK from source the
  way F-Droid does, and scans the APK with `fdroid scanner`;
- **Reproducible build** rebuilds the release APK on another runner, at another path, in another time zone, and
  fails unless both APKs are identical.

`pr-ci.yml` runs them on every pull request (plus "Dependency review" and "actionlint"), and `main-ci.yml` on
`main`. `codeql.yml` runs CodeQL; `dependency-submission.yml` submits the resolved Gradle graph to feed Dependabot;
`dependabot-automerge.yml` auto-merges Dependabot patch and minor updates once the checks pass.

### Releasing

Releases are reproducible: F-Droid rebuilds each tagged version from source and, when its APK matches ours byte for
byte (apart from the signature), publishes the APK from GitHub Releases signed with our key. So users can switch
between the F-Droid and GitHub APKs without reinstalling.

1. Actions → **Prepare release** → Run workflow with the `version` (`X.Y.Z`) and the release notes (at most 500
   characters). It opens a PR that sets `versionName`/`versionCode` (`X*10000 + Y*100 + Z`) in
   `app/build.gradle.kts`, the build entry in `.fdroid.yml` and
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. PRs opened with the default `GITHUB_TOKEN` don't
   start CI: set the `RELEASE_PR_TOKEN` secret (a fine-grained token with contents and pull-requests write), or
   close and reopen the PR.
2. Merge it, then run Actions → **Release** on `main`. It reruns the checks, builds marknotes-sync and the APK
   from source, signs it, checks the signer against `AllowedAPKSigningKeys` in `.fdroid.yml` and the
   `RELEASE_CERT_SHA256` variable, checks with `apksigcopier compare` that F-Droid's build (the Checks' F-Droid
   build) verifies against it, and publishes `marknotes-libre-X.Y.Z.apk` and `SHA256SUMS` in the `vX.Y.Z` release.
3. F-Droid's checkupdates bot sees the new tag (`UpdateCheckMode: Tags`) and builds it within a few days.

**Repository secrets**: `RELEASE_KEYSTORE_BASE64` (`base64 -w0 release.keystore`), `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, and optionally `RELEASE_PR_TOKEN`. **Variable**:
`RELEASE_CERT_SHA256`, the SHA-256 of the signing certificate. The signing key must never change: F-Droid only
accepts the key in `AllowedAPKSigningKeys`, and Android refuses updates signed with another key.

**Rollback**: Android refuses to install a lower `versionCode` over a higher one, so roll forward with a fix
released as a higher version.

Locally, `assembleRelease` signs only when `MARKNOTES_KEYSTORE_PATH` (plus the matching password/alias env vars) is
set; otherwise it stays unsigned.

## License

MarkNotes is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version (GPL-3.0-or-later). It is distributed WITHOUT ANY WARRANTY.

Third-party dependencies remain under their own licenses. Importing uses [jsoup](https://jsoup.org) (MIT) for HTML
and [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0) for PDF text.
