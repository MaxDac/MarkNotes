# AGENTS.md

Canonical instructions for coding agents (Claude Code, GitHub Copilot, others) working on **MarkNotes for
Android, libre build** (`io.github.maxdac.marknotes.libre`): Dropbox sync only, every dependency free software,
published on F-Droid and GitHub Releases. `CLAUDE.md` and `.github/copilot-instructions.md` point here; keep agent
guidance in this file.

MarkNotes is one of several apps that share the same notes folder through cloud drives. The on-disk format and sync
behaviour must stay identical across them (see [Cross-app parity](#cross-app-parity)).

## Sibling repositories

| Repo | What | Stack |
| --- | --- | --- |
| [MaxDac/marknotes-android](https://github.com/MaxDac/marknotes-android) | This repo: Android app, libre build (Dropbox), on F-Droid | Kotlin, Gradle, Jetpack Compose |
| [MaxDac/MarkNotes](https://github.com/MaxDac/MarkNotes) | MarkNotes Pro: this repo as the `libre/` submodule plus OneDrive and Proton Drive, GitHub releases only | Kotlin, Gradle |
| [MaxDac/marknotes-electron](https://github.com/MaxDac/marknotes-electron) | Desktop app (Windows, macOS, Linux) | Electron, TypeScript, CodeMirror |
| [MaxDac/marknotes-web](https://github.com/MaxDac/marknotes-web) | Web app | Phoenix (Elixir), TypeScript assets |
| [MaxDac/marknotes-sync](https://github.com/MaxDac/marknotes-sync) | Shared Rust sync core with UniFFI Kotlin bindings | Rust |
| [MaxDac/marknotes-spec](https://github.com/MaxDac/marknotes-spec) | Canonical spec: `note-format.md`, `features.md` (parity matrix), JSON golden fixtures under `fixtures/<area>/*.json` | Markdown, JSON |

The spec repo is checked out here as a git submodule at `spec/` (`git submodule update --init`). Read sibling
code with `gh` without cloning, for example
`gh api repos/MaxDac/marknotes-electron/contents/src -H "Accept: application/vnd.github.raw"` or
`gh search code --repo MaxDac/marknotes-web <term>`.

### This repo inside MarkNotes Pro

MaxDac/MarkNotes includes this repo as a submodule and builds `:core` and `:app-shared` from it, adding its own
`:app` (OneDrive, Proton Drive) through the `DriveConnector` extension points in `app-shared/.../sync/DriveConnector.kt`
and `MarkNotesApp`. So:

- keep `:core` and `:app-shared` provider-neutral and free of non-free code; provider-specific code goes in a
  `DriveConnector` (Dropbox lives in `:app-shared` because both apps ship it);
- changing a public API of `:core` or `:app-shared` (`DriveConnector`, `MarkNotesApp`, `AppContainer`, ...) breaks
  MarkNotes Pro until it bumps the submodule and adapts: say so in the PR and follow up there;
- dependencies both apps use go in `gradle/libs.versions.toml` here; MarkNotes Pro reads this catalog as `libs`.

## Build and test

Requirements: JDK 21+ (Android Studio's JBR works) and the Android SDK with platform 37. On Windows use
`.\gradlew.bat` and set `JAVA_HOME`/`ANDROID_HOME` if they aren't set.

These mirror the required CI checks in `.github/workflows/checks.yml` (do not rename the jobs: the `main`
ruleset requires them by name):

| CI check | Command |
| --- | --- |
| Checks / Lint (ktlint, detekt, Android Lint) | `./gradlew ktlintCheck detekt lintDebug --continue` (`ktlintFormat` auto-fixes style) |
| Checks / Unit tests | `./gradlew testDebugUnitTest :core:test --continue` |
| Checks / Instrumented tests (emulator) | `./gradlew :app:connectedDebugAndroidTest` (needs a running emulator/device) |
| Checks / Build debug APK | `./gradlew assembleDebug` |
| Checks / Parity (marknotes-spec fixtures) | `./gradlew :core:parityTest :app-shared:testDebugUnitTest --tests 'io.github.maxdac.marknotes.parity.*' -Pmarknotes.parity.required=true` (needs the `spec/` submodule) |
| Checks / F-Droid build | `scripts/fdroid-lint.sh` (needs fdroidserver), then the from-source build in the README, "Building from source, like F-Droid" (Linux), then `fdroid scanner --exit-code <apk>` |
| Checks / Reproducible build | The same from-source build twice at different paths; the unsigned APKs must be identical |

PRs also run **Dependency review** (fails on high-severity vulnerabilities, after submitting the resolved
Gradle graph), **actionlint** and **CodeQL**. Required checks and review-thread resolution are enforced by the
`main` ruleset (the single source of truth; there is no classic branch protection). Fast inner loop for
`:core` changes: `./gradlew :core:test`.

## Architecture

| Module | Contents |
| --- | --- |
| `:core` | Pure Kotlin/JVM, no Android APIs. `notes/` (`LocalNoteStore`, `FrontMatter` note IDs, `NoteIndex` and note links, `NotePaths`, `Attachments`, `ChordSequence`), `sync/` (`SyncEngine`, `MultiDriveSync`, `ConflictMergeBack`, `Hashing`, `RemoteDriveStore` interface). The sync algorithm itself (planning, three-way merge, conflicted copies, manifest, drive scheduling) comes from the shared Rust core [marknotes-sync](https://github.com/MaxDac/marknotes-sync) through its UniFFI Kotlin binding; `SyncEngine` only executes its plan against the local tree and a `RemoteDriveStore`. All format and sync logic lives here and is unit tested. |
| `:app-shared` | Android library with the app: Compose + Material 3, Navigation Compose, MVVM with `StateFlow`, Room (drive accounts, sync metadata), manual DI (`AppContainer`), `DriveConnector` extension points, Dropbox store and sign-in, encrypted token storage. |
| `:app` | The libre application: `LibreApp` (Dropbox connector only), manifest, instrumented tests. |

The sync engine only talks to `RemoteDriveStore`; changes to the sync algorithm go in marknotes-sync (with the spec), not here; tests use the in-memory `FakeRemoteDriveStore`. Put new
format, sync, link or attachment logic in `:core` so it can be tested on the JVM and against spec fixtures.

## Conventions

- Kotlin style is enforced by ktlint (`android_studio` style, 120 columns, see `.editorconfig`) and detekt
  (`config/detekt/detekt.yml`). Run `ktlintFormat` before committing.
- Dependencies go in `gradle/libs.versions.toml`.
- Sync changes need `SyncEngineTest`/`MultiDriveSyncTest` coverage; UI changes need an emulator run.
- Never commit secrets, keystores or `local.properties`. The Dropbox app key in `app/build.gradle.kts` is public by
  design (PKCE) and committed on purpose, so F-Droid can build a working app.
- Fill in the PR template, including **Parity impact**.

## F-Droid

F-Droid builds the app from source with the recipe in [`.fdroid.yml`](.fdroid.yml) (fdroiddata's copy,
`metadata/io.github.maxdac.marknotes.libre.yml`, takes precedence; see [docs/fdroid.md](docs/fdroid.md)). Rules:

- **Free software only**: no Google Play Services, Firebase, proprietary SDKs or analytics, and every Maven
  repository must be a free one F-Droid trusts (Google, Maven Central, Gradle Plugin Portal). The Checks / F-Droid
  build job runs `fdroid scanner` on the APK. Dropbox is a non-free network service: the *NonFreeNet* anti-feature.
- **No prebuilt binaries**: marknotes-sync is built from source (`scripts/build-sync-from-source.sh`,
  `-Pmarknotes.sync.fromSource=<checkout>`). Bumping `marknotesSync` in `gradle/libs.versions.toml` needs the
  `marknotes-sync@v<version>` srclib in `.fdroid.yml` bumped too (`scripts/fdroid-lint.sh` checks it) and a
  marknotes-sync release tag.
- **Reproducible**: F-Droid ships our signed APK only if its rebuild matches. Keep `vcsInfo` and `dependenciesInfo`
  off, keep the Rust/NDK/cargo-ndk pins in `scripts/build-sync-from-source.sh`, `.fdroid.yml` and
  `.github/actions/setup-sync-toolchain` identical, and don't add build inputs that depend on time, paths or the
  machine. The Checks / Reproducible build job catches regressions.
- **Version literals**: F-Droid reads `versionCode`/`versionName` from `app/build.gradle.kts`, so they stay literals
  (set by the Prepare release workflow). Store metadata is in `fastlane/metadata/android/en-US/` (one changelog per
  versionCode).

## Release and secrets

Releases are reproducible signed APKs: run **Prepare release** (`version`, notes), merge its PR, then run
**Release** on `main`. It builds from source exactly like F-Droid, signs with `RELEASE_KEYSTORE_BASE64`/
`RELEASE_KEYSTORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD`, checks the signer against
`AllowedAPKSigningKeys` and the `RELEASE_CERT_SHA256` variable, verifies F-Droid's build against it with
`apksigcopier compare`, and publishes `marknotes-libre-X.Y.Z.apk` plus `SHA256SUMS` (same checksum file name as the
desktop and web releases). The signing key must never change. Roll forward only: `versionCode` can't go down.
Details in the README, "Releasing".

## Cross-app parity

These areas are shared with the desktop and web apps and must behave identically:

- note format: files and folders, front-matter and note IDs (alphabet, lazy assignment, duplicates);
- note links (`marknotes:<id>`, escaping, `@` picker matching, broken links);
- attachments (`attachments/<sha256>.<ext>`, accepted types, size limits, cleanup);
- chord sequences (`type: chords` detection, body grammar, canonical serialisation, validation, display names);
- sync: `.marknotes/manifest.json` format, hashing, what is synced, three-way merge and conflict handling.

Rules:

1. Changes to any of these land in **marknotes-spec first** (`note-format.md`, `features.md`, and fixtures
   under `fixtures/<area>/`), then are mirrored here **and** in marknotes-electron and marknotes-web. Bump the
   `spec/` submodule in the same PR that implements the change.
2. Every fixture case in `spec/fixtures/` must pass in the parity tests: `CoreParity` (`core/src/test/.../parity/`)
   maps each fixture file to the real `:core` function (`note-links/mention` runs in `:app-shared`'s
   `MentionParityTest`), and the run fails on an unmapped fixture file. A case this app can't satisfy yet gets a
   `knownDivergence.android` reason in the spec repo (the test then asserts the output still differs and is skipped
   with that reason), never a silently weakened test here. Without the submodule the parity tests skip in
   `:core:test`/`testDebugUnitTest`; `:core:parityTest` and the CI job fail instead.
3. Features that aren't shared (for example Android-only UI) don't need spec changes, but update the
   `features.md` matrix when a user-visible feature is added, removed or changed.
4. To compare a feature across the apps, use the parity-check prompt: `/parity-check <feature>` in Claude Code
   (`.claude/commands/parity-check.md`) or `.github/prompts/parity-check.prompt.md` in Copilot.
