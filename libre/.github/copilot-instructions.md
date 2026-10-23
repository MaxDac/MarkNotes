# Copilot instructions

Read [`AGENTS.md`](../AGENTS.md) first: it is the canonical guide (build/test commands, architecture,
conventions, F-Droid, release, sibling repos). Key rules:

- MarkNotes for Android, libre build (Kotlin, Gradle): Dropbox sync only, published on F-Droid. Format, sync, link
  and attachment logic lives in the pure-JVM `:core` module; `:app-shared` is the Compose UI and app wiring,
  `:app` the libre application. [MaxDac/MarkNotes](https://github.com/MaxDac/MarkNotes) includes this repo as a
  submodule and adds OneDrive and Proton Drive through `DriveConnector`; keep `:core` and `:app-shared` free of
  provider-specific and non-free code.
- Before pushing, run what CI runs: `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :core:test assembleDebug`
  and, when `spec/` changes or parity logic changes, `./gradlew :core:parityTest :app-shared:testDebugUnitTest --tests 'io.github.maxdac.marknotes.parity.*' -Pmarknotes.parity.required=true`.
- F-Droid: free dependencies only (no Google Play Services, Firebase or proprietary SDKs), no prebuilt binaries;
  the release APK must stay reproducible (Checks / F-Droid build and Reproducible build). Keep `.fdroid.yml` in sync.
- Don't rename CI jobs in `.github/workflows/checks.yml` or `pr-ci.yml`: the `main` ruleset requires them by name.
- This app shares its notes folder with [marknotes-electron](https://github.com/MaxDac/marknotes-electron) and
  [marknotes-web](https://github.com/MaxDac/marknotes-web). Changes to the note format, note IDs, links,
  attachments, the sync manifest or merge behaviour land in
  [marknotes-spec](https://github.com/MaxDac/marknotes-spec) first (the `spec/` submodule) and are mirrored in
  all the apps. Use `.github/prompts/parity-check.prompt.md` to compare a feature across the apps.
- Never commit secrets, keystores or `local.properties`. Fill in the PR template's **Parity impact** section.
