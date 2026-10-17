# MarkNotes

Simple Android markdown notes app with folders and Proton Drive sync (unofficial).

> **This is a third-party application not officially supported by Proton.**
> MarkNotes is a personal, non-commercial project. It is not affiliated with or endorsed by Proton AG,
> and it does not use any Proton logos or branding.

## Features (v1)

- **Offline-first notes**: every note is a `.md` file in a folder tree inside the app's private storage
  (`filesDir/notes`). Folders map to real directories.
- **Folder browser**: create, open, rename and delete notes and folders.
- **Editor**: raw markdown editing with a toggle to a rendered preview (Markwon: tables, strikethrough,
  task lists). Changes are autosaved (debounced, and flushed when leaving the editor).
- **Proton Drive two-way sync** with a folder in *My files* (default `MarkNotes`):
  - runs on app launch (when logged in) and when you tap **Sync now**; there is no background or periodic polling;
  - only folders and `.md` files are synced;
  - conflicts (changed on both sides) show both versions and let you **keep local**, **keep remote** or
    **keep both** (the remote version is saved as `name (conflicted copy).md`).
- **Login**: Proton SRP password login with TOTP two-factor authentication. The session is stored
  encrypted with an Android Keystore AES-GCM key; tokens are refreshed before each sync; logout revokes
  the session. CAPTCHA / human verification is not supported: you get a clear error instead.

Out of scope for v1: search, tags, attachments, background sync, widgets, local encryption.

## Project layout

| Module | What it contains |
| --- | --- |
| `:core` | Pure Kotlin/JVM: local note store, sync engine, `RemoteDriveStore` interface, Proton SRP + auth client. Fully unit tested. |
| `:app` | Android app: Jetpack Compose + Material 3, Navigation Compose, MVVM with `StateFlow`, Room (sync metadata only), manual DI. |
| `:proton-drive` | Optional bridge to the experimental [Proton Drive SDK](https://github.com/ProtonDriveApps/sdk). Only included with `-Pmarknotes.protonSdk=true`. |

The sync engine only talks to the `RemoteDriveStore` interface, so SDK changes stay contained in
`:proton-drive`. Tests use an in-memory `FakeRemoteDriveStore`.

## Build

Requirements: JDK 17 and the Android SDK (compile SDK 35). The app runs on Android 10+ (minSdk 29,
required by the Proton Drive SDK). The Gradle wrapper (8.10.2) is included:

```sh
./gradlew assembleDebug test
```

CI (`.github/workflows/ci.yml`) runs `assembleDebug` and all unit tests. A second, non-blocking job
builds the app together with the Proton Drive SDK module.

### Building with the Proton Drive SDK

The default build does **not** include the SDK. The app then works fully offline, and the settings
screen explains that sync is unavailable. To include the SDK bridge:

```sh
./gradlew :app:assembleDebug -Pmarknotes.protonSdk=true
```

This adds `:proton-drive`, which depends on `me.proton.drive:sdk` from Maven Central (version in
`gradle/libs.versions.toml`; check [the published versions](https://mvnrepository.com/artifact/me.proton.drive/sdk)
and update it if needed). The SDK's Kotlin bindings wrap the C# SDK through JNI and need its
NativeAOT-compiled native library (`libproton_drive_sdk_jni.so`). If the published AAR doesn't
bundle the native libraries for your ABI, build them from the SDK repository:

1. Clone https://github.com/ProtonDriveApps/sdk and follow its build instructions for the C# SDK.
2. Publish NativeAOT for Android (`linux-bionic-arm64`, plus `linux-bionic-x64` for emulators) with the .NET 9+ SDK.
3. Copy the resulting `.so` files into `proton-drive/src/main/jniLibs/<abi>/`.
4. Or publish the Kotlin bindings (`incubating/client/kt`) to `mavenLocal()`, and add `mavenLocal()` to `settings.gradle.kts`.

### Finishing the Proton Drive SDK wiring

The SDK doesn't handle authentication or sessions, so MarkNotes ships its own login (`:core`
`ProtonAuthClient`). `ProtonDriveStore`, the `RemoteDriveStore` built on the SDK, is fully
implemented. However, `ProtonDriveStoreFactory.createClient` still returns `null`, so the app shows
"not fully wired yet" when you sync. The remaining steps are:

1. Build a protoncore `ApiProvider` that authenticates with the stored `ProtonSession`
   (UID + access token, with refresh) and sends `x-pm-appversion: external-drive-marknotes@0.1.0-alpha`.
2. Implement the SDK's `UserAddressResolver` and `PublicAddressResolver`, unlocking the user's address
   keys with the key passphrases derived at login (`ProtonSession.keyPassphrases`), or wire protoncore's
   account/key repositories and use the SDK's `CoreUserAddressResolver` / `CorePublicAddressResolver`.
3. Call `ProtonDriveSdk.protonDriveClientCreate(scope, userId, apiProvider, ClientCreateRequest(...), userAddressResolver, publicAddressResolver)`
   and return the client from `createClient`.
4. Verify how `enumerateEvents` behaves with a `null` cursor. `ProtonDriveStore` assumes it starts from
   the latest event and records the returned event id as its cursor.

Until then, everything except the network transfer is in place and tested: the sync engine,
conflict handling, metadata, login and the UI.

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

## Licensing notes

The Proton Drive SDK is GPLv3, so builds that include `:proton-drive` must respect the GPLv3. The
SRP implementation in `:core` was written for this project, following the public protocol as
implemented in Proton's MIT-licensed [go-srp](https://github.com/ProtonMail/go-srp). No
protoncore_android code was copied.
