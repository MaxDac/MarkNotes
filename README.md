# MarkNotes

Simple Android markdown notes app with folders and sync to Proton Drive (unofficial), Dropbox and OneDrive.

> **This is a third-party application not officially supported by Proton.**
> MarkNotes is a personal, non-commercial project. It is not affiliated with or endorsed by Proton AG,
> and it does not use any Proton logos or branding.

## Features

- **Offline-first notes**: every note is a `.md` file in a folder tree inside the app's private storage
  (`filesDir/notes`). Folders map to real directories.
- **Folder system**: create, open, rename, delete and **move** notes and folders (item menu → "Move to…").
  A navigation drawer shows the whole folder tree so you can jump straight to any folder.
- **Editor**: raw markdown editing with a toggle to a rendered preview (Markwon: tables, strikethrough,
  task lists). Changes are autosaved (debounced, and flushed when leaving the editor).
- **Appearance** (Settings): theme **System** (default), **Light** or **Dark**, and an accent colour chosen
  from 8 palettes (Blue, Teal, Green, Lime, Amber, Orange, Red, Purple) or **Dynamic** (Material You,
  Android 12+, the default where available).
- **Multi-drive two-way sync**: connect any number of **Proton Drive**, **Dropbox** and **OneDrive** accounts
  (Settings → Add drive). Every drive mirrors the same local note tree:
  - for Dropbox and OneDrive you browse to a folder and can optionally create a root folder inside it
    (default `MarkNotes`); for Proton Drive you pick a folder name in *My files*;
  - sync runs on app launch and when you tap **Sync now**; there is no background or periodic polling;
  - only folders and `.md` files are synced. Each drive keeps its own sync state, plus a small manifest at
    `.marknotes/manifest.json` on the drive (content hashes and the device that last wrote each note);
  - when only one side changed, that version wins. Deletions and moves propagate (a move is a delete
    plus a create);
  - edits made on both sides are merged line by line (a three-way merge against the last synced version).
    Only overlapping edits become **conflicts**. For each conflicting change you pick **Mine**, **Theirs**,
    **Both** or **Neither**, or you edit the merged text by hand (with git-style `<<<<<<<` markers). You can
    also keep either whole version, or keep both files (the remote one is saved as `name (conflicted copy).md`).
- **Proton login**: Proton SRP password login with TOTP two-factor authentication. CAPTCHA / human
  verification is not supported: you get a clear error instead.
- **Dropbox / OneDrive login**: the official Dropbox SDK (OAuth 2 PKCE) and Microsoft MSAL.
- Sessions and tokens are stored encrypted with an Android Keystore AES-GCM key. Disconnecting a drive
  signs you out, and revokes the session where the provider supports it.

Out of scope: search, tags, attachments, background sync, widgets, local encryption.

## Project layout

| Module | What it contains |
| --- | --- |
| `:core` | Pure Kotlin/JVM: local note store, three-way merge, sync engine (single and multi-drive), `RemoteDriveStore` interface, OneDrive (Microsoft Graph) store, Proton SRP + auth client. Fully unit tested. |
| `:app` | Android app: Jetpack Compose + Material 3, Navigation Compose, MVVM with `StateFlow`, Room (drive accounts and sync metadata), manual DI, Dropbox store, Dropbox/MSAL sign-in. |
| `:proton-drive` | Optional bridge to the experimental [Proton Drive SDK](https://github.com/ProtonDriveApps/sdk). Only included with `-Pmarknotes.protonSdk=true`. |

The sync engine only talks to the `RemoteDriveStore` interface, so SDK changes stay contained in
`:proton-drive`. Tests use an in-memory `FakeRemoteDriveStore`.

## Build

Requirements: JDK 21+ (Android Studio's bundled JBR works) and the Android SDK with platform 37
(compileSdk 37, targetSdk 36). The app runs on Android 10+ (minSdk 29, required by the Proton Drive SDK).
The Gradle wrapper (9.8.1, AGP 9.4 with built-in Kotlin 2.4) is included:

```sh
./gradlew assembleDebug                      # build the debug APK
./gradlew ktlintCheck detekt lintDebug       # ktlint, detekt and Android Lint (ktlintFormat auto-fixes style)
./gradlew testDebugUnitTest :core:test       # unit tests (:core is a plain JVM module)
./gradlew :app:connectedDebugAndroidTest     # Compose UI tests on a running emulator/device
```

CI mirrors these commands. The checks live in the reusable `.github/workflows/checks.yml`: four required
checks, "Lint (ktlint, detekt, Android Lint)", "Unit tests", "Instrumented tests (emulator)" and
"Build debug APK", plus a non-blocking job that builds the app together with the Proton Drive SDK module.
`pr-ci.yml` runs them on every pull request, and `main-ci.yml` reruns them on `main` and builds an unsigned
release APK.

### Releasing

`release.yml` is started manually (Actions → Release → Run workflow) with a `version` (`X.Y.Z`, default
`0.1.0`) and a `prerelease` flag. It runs the checks, builds a signed APK (`versionName` = version,
`versionCode` = `X*10000 + Y*100 + Z`), and publishes a `vX.Y.Z` GitHub Release with the APK and its SHA-256.
Signing uses the repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. Locally, `assembleRelease` signs only when
`MARKNOTES_KEYSTORE_PATH` (plus the matching password/alias env vars) is set; otherwise it stays unsigned.

### Configuring Dropbox and OneDrive

Both providers need app registrations. Without them the build still works, and "Add drive" shows the
provider as not configured. Put the values in `~/.gradle/gradle.properties` (or pass them with `-P`):

```properties
marknotes.dropboxAppKey=<Dropbox app key>
marknotes.msalClientId=<Azure application (client) ID>
marknotes.msalSignatureHash=<base64 SHA-1 of the signing certificate>
```

- **Dropbox**: create an app in the [Dropbox App Console](https://www.dropbox.com/developers/apps) with
  "Full Dropbox" access and the scopes `account_info.read`, `files.metadata.read`, `files.content.read` and
  `files.content.write`. PKCE needs no app secret. The SDK's `AuthActivity` handles the redirect
  (scheme `db-<app key>`).
- **OneDrive**: register an app in [Microsoft Entra](https://entra.microsoft.com) for personal Microsoft
  accounts and organizational accounts, with the delegated permissions `Files.ReadWrite` and `User.Read`.
  Add an **Android** platform with package `io.github.maxdac.marknotes` and the signature hash. Azure then
  shows the redirect URI `msauth://io.github.maxdac.marknotes/<URL-encoded hash>`, which is the one
  MarkNotes uses. To get the debug hash, run
  `keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore | openssl sha1 -binary | openssl base64`.
  On Windows without openssl, copy the `SHA1` line printed by `.\gradlew :app:signingReport` and convert it
  in PowerShell:
  `[Convert]::ToBase64String([byte[]]('AA:BB:...' -split ':' | ForEach-Object { [Convert]::ToByte($_, 16) }))`.
  The hash depends on the signing key, so an APK signed with a different key (another PC's debug key, or
  the release key) fails to sign in to OneDrive. Add one Android platform entry per key in Entra.
- **Release builds**: `release.yml` reads the repository secrets `DROPBOX_APP_KEY`, `MSAL_CLIENT_ID` and
  `MSAL_RELEASE_SIGNATURE_HASH` (the hash of the release keystore). If they are missing, the release APK
  shows both providers as not configured.

### Building with the Proton Drive SDK

The default build does **not** include the SDK. Proton Drive is then shown as unavailable in
"Add drive"; Dropbox and OneDrive are not affected. To include the SDK bridge:

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
