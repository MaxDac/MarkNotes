# AGENTS.md

Canonical instructions for coding agents (Claude Code, GitHub Copilot, others) working on **MarkNotes for
Android**. `CLAUDE.md` and `.github/copilot-instructions.md` point here; keep agent guidance in this file.

MarkNotes is one of three apps that share the same notes folder through Proton Drive, Dropbox and OneDrive.
The on-disk format and sync behaviour must stay identical across them (see [Cross-app parity](#cross-app-parity)).

## Sibling repositories

| Repo | What | Stack |
| --- | --- | --- |
| [MaxDac/MarkNotes](https://github.com/MaxDac/MarkNotes) | This repo: Android app | Kotlin, Gradle, Jetpack Compose |
| [MaxDac/marknotes-electron](https://github.com/MaxDac/marknotes-electron) | Desktop app (Windows, macOS, Linux) | Electron, TypeScript, CodeMirror |
| [MaxDac/marknotes-web](https://github.com/MaxDac/marknotes-web) | Web app | Phoenix (Elixir), TypeScript assets |
| [MaxDac/marknotes-spec](https://github.com/MaxDac/marknotes-spec) | Canonical spec: `note-format.md`, `features.md` (parity matrix), JSON golden fixtures under `fixtures/<area>/*.json` | Markdown, JSON |

The spec repo is checked out here as a git submodule at `spec/` (`git submodule update --init`). Read sibling
code with `gh` without cloning, for example
`gh api repos/MaxDac/marknotes-electron/contents/src -H "Accept: application/vnd.github.raw"` or
`gh search code --repo MaxDac/marknotes-web <term>`.

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
| Checks / Parity (marknotes-spec fixtures) | `./gradlew :core:parityTest :app:testDebugUnitTest --tests 'io.github.maxdac.marknotes.parity.*' -Pmarknotes.parity.required=true` (needs the `spec/` submodule) |

PRs also run **Dependency review** (fails on high-severity vulnerabilities, after submitting the resolved
Gradle graph), **actionlint** and **CodeQL**. Required checks and review-thread resolution are enforced by the
`main` ruleset (the single source of truth; there is no classic branch protection). Fast inner loop for
`:core` changes: `./gradlew :core:test`.

## Architecture

| Module | Contents |
| --- | --- |
| `:core` | Pure Kotlin/JVM, no Android APIs. `notes/` (`LocalNoteStore`, `FrontMatter` note IDs, `NoteIndex` and note links, `NotePaths`, `Attachments`, `ChordSequence`), `sync/` (`SyncEngine`, `MultiDriveSync`, `ConflictMergeBack`, `Hashing`, `RemoteDriveStore` interface, OneDrive store), `auth/` (Proton SRP and auth client). The sync algorithm itself (planning, three-way merge, conflicted copies, manifest, drive scheduling) comes from the shared Rust core [marknotes-sync](https://github.com/MaxDac/marknotes-sync) through its UniFFI Kotlin binding; `SyncEngine` only executes its plan against the local tree and a `RemoteDriveStore`. All format and sync logic lives here and is unit tested. |
| `:app` | Android UI and wiring: Compose + Material 3, Navigation Compose, MVVM with `StateFlow`, Room (drive accounts, sync metadata), manual DI, Dropbox store, Dropbox/MSAL sign-in, encrypted token storage. |
| `:proton-drive` | Proton Drive `RemoteDriveStore` on the Proton Drive SDK (JNI, `arm64-v8a`/`x86_64` only) plus the protoncore network stack and address-key resolvers. |

The sync engine only talks to `RemoteDriveStore`; changes to the sync algorithm go in marknotes-sync (with the spec), not here; tests use the in-memory `FakeRemoteDriveStore`. Put new
format, sync, link or attachment logic in `:core` so it can be tested on the JVM and against spec fixtures.

## Conventions

- Kotlin style is enforced by ktlint (`android_studio` style, 120 columns, see `.editorconfig`) and detekt
  (`config/detekt/detekt.yml`). Run `ktlintFormat` before committing.
- Dependencies go in `gradle/libs.versions.toml`. Never bump `me.proton.core` independently of the Proton
  Drive SDK it is built against (Dependabot auto-merge skips it on purpose).
- Sync changes need `SyncEngineTest`/`MultiDriveSyncTest` coverage; UI changes need an emulator run.
- Proton rules: every request sends `x-pm-appversion: external-drive-marknotes@0.1.0-alpha`, only official
  endpoints, no Proton logos/branding, keep the "third-party application not officially supported by Proton"
  disclaimer on the login screen, no frequent recursive polling.
- Never commit secrets, keystores or `local.properties`. Provider keys come from `~/.gradle/gradle.properties`
  or `-P` flags (see `docs/cloud-providers.md`).
- Fill in the PR template, including **Parity impact**.

## Release and secrets

Releases are signed APKs published by `release.yml` (manual dispatch with `version` `X.Y.Z`). It reruns the
checks, signs with `RELEASE_KEYSTORE_BASE64`/`RELEASE_KEYSTORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD`,
pins the signer to the `RELEASE_CERT_SHA256` variable when set, fails without `DROPBOX_APP_KEY`,
`MSAL_CLIENT_ID` and `MSAL_RELEASE_SIGNATURE_HASH` unless `allow-missing-providers` is ticked, and publishes
`marknotes-X.Y.Z.apk` plus `SHA256SUMS` (same checksum file name as the desktop and web releases). Roll
forward only: `versionCode` can't go down. Details in the README, "Releasing".

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
   maps each fixture file to the real `:core` function (`note-links/mention` runs in `:app`'s `MentionParityTest`),
   and the run fails on an unmapped fixture file. A case this app can't satisfy yet gets a `knownDivergence.android`
   reason in the spec repo (the test then asserts the output still differs and is skipped with that reason), never a
   silently weakened test here. Without the submodule the parity tests skip in `:core:test`/`testDebugUnitTest`;
   `:core:parityTest` and the CI job fail instead.
3. Features that aren't shared (for example Android-only UI) don't need spec changes, but update the
   `features.md` matrix when a user-visible feature is added, removed or changed.
4. To compare a feature across the apps, use the parity-check prompt: `/parity-check <feature>` in Claude Code
   (`.claude/commands/parity-check.md`) or `.github/prompts/parity-check.prompt.md` in Copilot.
