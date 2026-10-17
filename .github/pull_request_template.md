## Summary

<!-- What does this PR change and why? -->

## Checklist

- [ ] `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :core:test assembleDebug` passes locally
- [ ] Sync changes are covered by `SyncEngineTest` (against `FakeRemoteDriveStore`)
- [ ] UI changes tested on an emulator/device (`./gradlew :app:connectedDebugAndroidTest`)
- [ ] Proton requirements still met (`x-pm-appversion` header, official endpoints only, no Proton branding, third-party disclaimer on login)