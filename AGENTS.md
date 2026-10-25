# AGENTS.md

Canonical instructions for coding agents (Claude Code, GitHub Copilot, others) working on **MarkNotes Pro for
Android** (`io.github.maxdac.marknotes`): the libre app plus OneDrive and Proton Drive sync, released on GitHub only.
`CLAUDE.md` and `.github/copilot-instructions.md` point here; keep agent guidance in this file.

MarkNotes is one of several apps that share the same notes folder through Proton Drive, Dropbox and OneDrive.
The on-disk format and sync behaviour must stay identical across them (see [Cross-app parity](#cross-app-parity)).

## Two repositories, one app

The app is split in two:

- [MaxDac/marknotes-android](https://github.com/MaxDac/marknotes-android), checked out here as the **`libre/`
  submodule**: the free-software app (Dropbox only, `io.github.maxdac.marknotes.libre`, on F-Droid). It holds
  `:core` (format, sync, links, attachments) and `:app-shared` (the whole Compose app, Dropbox), plus the spec
  submodule at `libre/spec`.
- This repo: the Pro modules only (`:pro-core`, `:proton-drive`, `:app`), which build `:core` and `:app-shared`
  from `libre/` and plug OneDrive and Proton Drive in through the `DriveConnector` extension points.

So:

- **Shared code changes go to marknotes-android first** (a PR there), then a PR here bumps the `libre/`
  submodule (Dependabot opens these weekly) and adapts the Pro code if an API changed. Don't edit files under
  `libre/` in a MarkNotes PR: they would be lost on the next bump. Follow marknotes-android's `AGENTS.md` for that
  code (no non-free code, reproducible F-Droid build).
- Only OneDrive, Proton Drive and Pro-only UI (the Proton login screen) live here.
- Dependencies used by shared code come from `libre/gradle/libs.versions.toml` (the `libs` catalog);
  Pro-only dependencies go in `gradle/libs.versions.toml` (the `pro` catalog).
- marknotes-sync routing and verification come from Libre's `libre/gradle/marknotes-sync.settings.gradle`;
  Pro owns the root `gradle/verification-metadata.xml` (sync artifacts only). When a `libre/` bump changes the
  sync entries in `libre/gradle/verification-metadata.xml`, copy them in the same PR and run
  `python3 libre/scripts/sync-contract.py --verification-root .` (see `docs/cloud-providers.md`).

Clone with `git clone --recurse-submodules`, or run `git submodule update --init --recursive` after cloning: the
build fails without `libre/`.

## Sibling repositories

| Repo | What | Stack |
| --- | --- | --- |
| [MaxDac/MarkNotes](https://github.com/MaxDac/MarkNotes) | This repo: MarkNotes Pro for Android (OneDrive, Proton Drive) | Kotlin, Gradle |
| [MaxDac/marknotes-android](https://github.com/MaxDac/marknotes-android) | The libre Android app and the shared code, `libre/` here | Kotlin, Gradle, Jetpack Compose |
| [MaxDac/marknotes-electron](https://github.com/MaxDac/marknotes-electron) | Desktop app (Windows, macOS, Linux) | Electron, TypeScript, CodeMirror |
| [MaxDac/marknotes-web](https://github.com/MaxDac/marknotes-web) | Web app | Phoenix (Elixir), TypeScript assets |
| [MaxDac/marknotes-sync](https://github.com/MaxDac/marknotes-sync) | Shared Rust sync core with UniFFI Kotlin bindings | Rust |
| [MaxDac/marknotes-spec](https://github.com/MaxDac/marknotes-spec) | Canonical spec: `note-format.md`, `features.md` (parity matrix), JSON golden fixtures under `fixtures/<area>/*.json` | Markdown, JSON |

The spec repo is checked out at `libre/spec/` (a submodule of marknotes-android). Read sibling code with `gh`
without cloning, for example
`gh api repos/MaxDac/marknotes-electron/contents/src -H "Accept: application/vnd.github.raw"` or
`gh search code --repo MaxDac/marknotes-web <term>`.

## Build and test

Requirements: JDK 21+ (Android Studio's JBR works) and the Android SDK with platform 37. On Windows use
`.\gradlew.bat` and set `JAVA_HOME`/`ANDROID_HOME` if they aren't set.

If `libre/gradle/marknotes-sync-source.properties` exists, build the pinned sync artifacts before Gradle:
on Linux run `libre/scripts/build-sync-from-source.sh` with the toolchain from
`libre/.github/actions/setup-sync-toolchain`. CI installs that toolchain and builds the exact source pin.
See `docs/cloud-providers.md`, "Development sync source builds". Do not substitute older artifacts or publish
a speculative release; signed release validation rejects development source pins.

These mirror the required CI checks in `.github/workflows/checks.yml` (do not rename the jobs: the `main`
ruleset requires them by name). They cover the shared modules from `libre/` too:

| CI check | Command |
| --- | --- |
| Checks / Lint (ktlint, detekt, Android Lint) | `./gradlew ktlintCheck detekt lintDebug --continue` (`ktlintFormat` auto-fixes style) |
| Checks / Unit tests | `./gradlew testDebugUnitTest :core:test :pro-core:test --continue` |
| Checks / Instrumented tests (emulator) | `./gradlew :app:connectedDebugAndroidTest` (needs a running emulator/device) |
| Checks / Build debug APK | `./gradlew assembleDebug` |
| Checks / Parity (marknotes-spec fixtures) | `./gradlew :core:parityTest :app-shared:testDebugUnitTest --tests 'io.github.maxdac.marknotes.parity.*' -Pmarknotes.parity.required=true` (needs `libre/spec`) |

PRs also run **Dependency review** (fails on high-severity vulnerabilities, after submitting the resolved
Gradle graph), **actionlint** and **CodeQL**. Required checks and review-thread resolution are enforced by the
`main` ruleset (the single source of truth; there is no classic branch protection).

## Architecture

| Module | Where | Contents |
| --- | --- | --- |
| `:core` | `libre/core` | Pure Kotlin/JVM, no Android APIs: notes, links, attachments, chord sequences, `SyncEngine`/`MultiDriveSync` executing the plans of [marknotes-sync](https://github.com/MaxDac/marknotes-sync), the `RemoteDriveStore` interface. |
| `:app-shared` | `libre/app-shared` | The Android app as a library: Compose UI, `AppContainer` DI, Room, `DriveConnector` extension points, Dropbox. |
| `:pro-core` | `pro-core` | Pure Kotlin/JVM: OneDrive (Microsoft Graph) `RemoteDriveStore`, Proton SRP and auth client. Unit tested. |
| `:proton-drive` | `proton-drive` | Proton Drive `RemoteDriveStore` on the Proton Drive SDK (JNI, `arm64-v8a`/`x86_64` only) plus the protoncore network stack and address-key resolvers. |
| `:app` | `app` | The Pro application: `ProApp` registers the Dropbox, OneDrive and Proton `DriveConnector`s; MSAL sign-in, Proton login screen, keystore session storage. |

The sync engine only talks to `RemoteDriveStore`; changes to the sync algorithm go in marknotes-sync (with the
spec), not here; tests use the in-memory `FakeRemoteDriveStore`.

## Conventions

- Kotlin style is enforced by ktlint (`android_studio` style, 120 columns, see `.editorconfig`) and detekt
  (`config/detekt/detekt.yml`). Run `ktlintFormat` before committing.
- Never bump `me.proton.core` independently of the Proton Drive SDK it is built against (Dependabot auto-merge
  skips it on purpose).
- Sync changes need `SyncEngineTest`/`MultiDriveSyncTest` coverage (in marknotes-android); UI changes need an
  emulator run.
- Proton rules: every request sends `x-pm-appversion: external-drive-marknotes@0.1.0-alpha`, only official
  endpoints, no Proton logos/branding, keep the "third-party application not officially supported by Proton"
  disclaimer on the login screen, no frequent recursive polling.
- Never commit secrets, keystores or `local.properties`. Provider keys come from `~/.gradle/gradle.properties`
  or `-P` flags (see `docs/cloud-providers.md`).
- Fill in the PR template, including **Parity impact**.

## Release and secrets

Releases are signed APKs on GitHub Releases only, published by `release.yml` (manual dispatch with `version`
`X.Y.Z`). It reruns the checks, signs with `RELEASE_KEYSTORE_BASE64`/`RELEASE_KEYSTORE_PASSWORD`/`RELEASE_KEY_ALIAS`/
`RELEASE_KEY_PASSWORD`, pins the signer to the `RELEASE_CERT_SHA256` variable when set, fails without
`DROPBOX_APP_KEY`, `MSAL_CLIENT_ID` and `MSAL_RELEASE_SIGNATURE_HASH` unless `allow-missing-providers` is ticked,
and publishes `marknotes-X.Y.Z.apk` plus `SHA256SUMS` (same checksum file name as the desktop and web releases).
Roll forward only: `versionCode` can't go down. Details in the README, "Releasing".

## Cross-app parity

These areas are shared with the desktop and web apps and must behave identically:

- note format: files and folders, front-matter and note IDs (alphabet, lazy assignment, duplicates);
- note links (`marknotes:<id>`, escaping, `@` picker matching, broken links);
- attachments (`attachments/<sha256>.<ext>`, accepted types, size limits, cleanup);
- chord sequences (`type: chords` detection, body grammar, canonical serialisation, validation, display names);
- sync: `.marknotes/manifest.json` format, hashing, what is synced, three-way merge and conflict handling.

All of it is implemented in marknotes-android (`libre/`), so the rules there apply: changes land in
**marknotes-spec first**, then in marknotes-android (which bumps its `spec/` submodule), marknotes-electron and
marknotes-web, and finally here as a `libre/` bump. The Parity check runs every `libre/spec/fixtures` case here
too. Update the `features.md` matrix when a user-visible feature is added, removed or changed (Pro-only
providers included). To compare a feature across the apps, use the parity-check prompt: `/parity-check <feature>`
in Claude Code (`.claude/commands/parity-check.md`) or `.github/prompts/parity-check.prompt.md` in Copilot.
