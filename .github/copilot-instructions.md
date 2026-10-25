# Copilot instructions

Read [`AGENTS.md`](../AGENTS.md) first: it is the canonical guide (build/test commands, architecture,
conventions, release, sibling repos). Key rules:

- MarkNotes Plus for Android (the Pro edition; Kotlin, Gradle): the libre app from [marknotes-android](https://github.com/MaxDac/marknotes-android)
  (the `libre/` submodule: `:core` logic and the `:app-shared` Compose app) plus OneDrive and Proton Drive
  (`:pro-core`, `:proton-drive`, `:app`). Change shared code in marknotes-android first, then bump `libre/` here;
  never edit `libre/` in a MarkNotes PR. Clone with `--recurse-submodules`.
- Before pushing, run what CI runs: `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :core:test :pro-core:test assembleDebug`
  and, when `libre/` changes or parity logic changes, `./gradlew :core:parityTest :app-shared:testDebugUnitTest --tests 'io.github.maxdac.marknotes.parity.*' -Pmarknotes.parity.required=true`.
- Don't rename CI jobs in `.github/workflows/checks.yml` or `pr-ci.yml`: the `main` ruleset requires them by name.
- This app shares its notes folder with [marknotes-electron](https://github.com/MaxDac/marknotes-electron) and
  [marknotes-web](https://github.com/MaxDac/marknotes-web). Changes to the note format, note IDs, links,
  attachments, the sync manifest or merge behaviour land in
  [marknotes-spec](https://github.com/MaxDac/marknotes-spec) first (at `libre/spec/`) and are mirrored in
  all the apps. Use `.github/prompts/parity-check.prompt.md` to compare a feature across the apps.
- Follow Proton's third-party rules (`x-pm-appversion` header, official endpoints, no Proton branding).
- Never commit secrets, keystores or `local.properties`. Fill in the PR template's **Parity impact** section.
