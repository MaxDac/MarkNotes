## Summary

<!-- What does this PR change and why? -->

## Parity impact

<!--
Does this touch behaviour shared with marknotes-electron and marknotes-web (note format, note IDs, links,
attachments, sync manifest, merge/conflicts) or the features.md matrix? See AGENTS.md, "Cross-app parity".
-->

- [ ] None: Android-only change
- [ ] Shared behaviour: spec PR in MaxDac/marknotes-spec: <!-- link --> and the `spec/` submodule is bumped here
- [ ] Desktop/web follow-ups: <!-- links to PRs or issues in marknotes-electron / marknotes-web -->

## Checklist

- [ ] `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :core:test assembleDebug` passes locally
- [ ] Parity tests pass (`./gradlew :core:parityTest` + `:app-shared` `MentionParityTest`, see AGENTS.md) (when shared behaviour or `spec/` changed)
- [ ] Sync changes are covered by `SyncEngineTest` (against `FakeRemoteDriveStore`)
- [ ] UI changes tested on an emulator/device (`./gradlew :app:connectedDebugAndroidTest`)
- [ ] F-Droid: no non-free dependencies or Google Play Services, and `.fdroid.yml` updated when the build changes (Checks / F-Droid build and Reproducible build pass)
