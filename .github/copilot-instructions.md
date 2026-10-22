# Copilot instructions

Read [`AGENTS.md`](../AGENTS.md) first: it is the canonical guide (build/test commands, architecture,
conventions, release, sibling repos). Key rules:

- Android app (Kotlin, Gradle). Format, sync, link and attachment logic lives in the pure-JVM `:core` module;
  `:app` is Compose UI, `:proton-drive` wraps the Proton Drive SDK.
- Before pushing, run what CI runs: `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :core:test assembleDebug`
  and, when `spec/` changes or parity logic changes, `./gradlew :core:parityTest`.
- Don't rename CI jobs in `.github/workflows/checks.yml` or `pr-ci.yml`: the `main` ruleset requires them by name.
- This app shares its notes folder with [marknotes-electron](https://github.com/MaxDac/marknotes-electron) and
  [marknotes-web](https://github.com/MaxDac/marknotes-web). Changes to the note format, note IDs, links,
  attachments, the sync manifest or merge behaviour land in
  [marknotes-spec](https://github.com/MaxDac/marknotes-spec) first (the `spec/` submodule) and are mirrored in
  all three apps. Use `.github/prompts/parity-check.prompt.md` to compare a feature across the apps.
- Follow Proton's third-party rules (`x-pm-appversion` header, official endpoints, no Proton branding).
- Never commit secrets, keystores or `local.properties`. Fill in the PR template's **Parity impact** section.
