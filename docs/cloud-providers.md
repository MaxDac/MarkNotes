# Configuring cloud providers (Dropbox, OneDrive, Proton Drive)

MarkNotes signs in to Dropbox with the official Dropbox SDK (OAuth 2 PKCE) and to OneDrive with Microsoft MSAL.
Both need an app registration. Without one the build still works, but "Add drive" shows the provider as
not configured.

The build reads three Gradle properties (see `app/build.gradle.kts`):

| Property | Value |
| --- | --- |
| `marknotes.dropboxAppKey` | Dropbox app key |
| `marknotes.msalClientId` | Entra application (client) ID |
| `marknotes.msalSignatureHash` | Base64 SHA-1 of the APK signing certificate |

Put them in your user-level `~/.gradle/gradle.properties` (on Windows `%USERPROFILE%\.gradle\gradle.properties`)
or pass them with `-P`. Never commit them to the repository.

This applies to the Pro app (this repo's `:app`). The libre app in `libre/` ([marknotes-android](https://github.com/MaxDac/marknotes-android))
has only Dropbox, and its Dropbox app key is committed in `libre/app/build.gradle.kts`, so F-Droid can build it.

## 1. Signature hash (for OneDrive)

MSAL checks that the APK is signed by the certificate registered in Entra. For local builds that's the
Android debug key.

1. Print the SHA-1 of the debug certificate. Android Studio bundles a JDK, so `JAVA_HOME` doesn't need to be set:

   ```powershell
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v `
     -alias androiddebugkey -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android |
     Select-String "SHA1:"
   ```

   On macOS/Linux,
   `keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android | openssl sha1 -binary | openssl base64`
   prints the final hash directly.

2. Convert the `AA:BB:...` SHA-1 to base64:

   ```powershell
   [Convert]::ToBase64String([byte[]]('AA:BB:...' -split ':' | ForEach-Object { [Convert]::ToByte($_, 16) }))
   ```

   The result (for example `vllhb+NmrYFNQTV2VMR7/n6URuk=`) is `marknotes.msalSignatureHash`.

The hash depends on the signing key. Every machine's debug key and the release key each produce a different
hash, and each one needs its own Android redirect URI in Entra (step 3).

## 2. Dropbox

1. Open the [Dropbox App Console](https://www.dropbox.com/developers/apps) and click **Create app**.
2. Choose **Scoped access** and **Full Dropbox**, give the app a name, and create it.
3. On the **Permissions** tab, enable `account_info.read`, `files.metadata.read`, `files.content.read` and
   `files.content.write`, then click **Submit**.
4. On the **Settings** tab, copy the **App key** into `marknotes.dropboxAppKey`. PKCE needs no app secret.

The SDK's `AuthActivity` handles the redirect with the scheme `db-<app key>`, which the manifest fills in
automatically. Nothing else needs registering.

A new Dropbox app starts in **development** status, which limits how many users can link it. Apply for
production status in the App Console before you distribute the APK widely.

## 3. OneDrive (Microsoft Entra)

1. Open [Microsoft Entra](https://entra.microsoft.com) → **App registrations** → **New registration**.
2. Enter a name. For **Supported account types** choose **Any Entra ID Tenant + Personal Microsoft accounts**,
   because the app uses the `AzureADandPersonalMicrosoftAccount` audience and personal OneDrive needs it.
   Leave **Redirect URI** empty and click **Register**.
   - If changing the account type later fails with `Property api.requestedAccessTokenVersion is invalid`, open
     **Manifest**, set `"requestedAccessTokenVersion": 2` inside the `"api"` block, save, and try again.
3. On **Overview**, copy the **Application (client) ID** into `marknotes.msalClientId`.
4. Go to **Authentication** → **Add Redirect URI** (called **Add a platform** in the old experience) →
   **Android**. Enter package name `io.github.maxdac.marknotes` and the signature hash from step 1. Entra
   shows the redirect URI `msauth://io.github.maxdac.marknotes/<URL-encoded hash>`, which MarkNotes builds
   on its own. Add one Android entry per signing key.
5. Go to **API permissions** → **Add a permission** → **Microsoft Graph** → **Delegated permissions**, and add
   `Files.ReadWrite`. `User.Read` is usually there already; add it if not. No admin consent is needed.

## 4. Build and verify

```powershell
.\gradlew :app:assembleDebug
```

Check that `app/build/generated/source/buildConfig/debug/.../BuildConfig.java` contains your values and not
`"unconfigured"`. Then install the APK and connect each provider from **Add drive**.

If Gradle reports `JAVA_HOME is not set` or `SDK location not found`, point it at Android Studio's JDK and
the SDK for the current shell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

## 5. Web and desktop apps

[marknotes-web](https://github.com/MaxDac/marknotes-web) (Phoenix LiveView) and
[marknotes-electron](https://github.com/MaxDac/marknotes-electron) reuse **the same Dropbox app and Entra
app**. Every client then uses the same app key and client ID, and all of them work on the same Dropbox and
OneDrive files. Each client type only needs its own redirect URIs.

**Dropbox** (App Console → your app → **Settings**):

1. Under **OAuth 2 → Allow public clients (Implicit Grant & PKCE)**, select **Allow**.
2. Under **OAuth 2 → Redirect URIs**, add:
   - `https://marknotes-web.fly.dev/oauth/dropbox` (web, production)
   - `http://localhost:4000/oauth/dropbox` (web, local `mix phx.server`)
   - `http://127.0.0.1/oauth/dropbox` (desktop PKCE loopback on a random port; Dropbox ignores the port for
     loopback URIs)

**Entra** (your app → **Authentication** → **Add Redirect URI**):

1. **Single-page application**: `https://marknotes-web.fly.dev/oauth/onedrive` and
   `http://localhost:4000/oauth/onedrive` (MSAL.js in the browser).
2. **Mobile and desktop applications**: `http://localhost` (MSAL Node with a loopback redirect on a random
   port). The desktop app requests `Files.ReadWrite User.Read offline_access`.

The Android, SPA and desktop entries can coexist on the same registration. Supported accounts and API
permissions stay as in step 3.

**Secrets:**

| App | Where | Names |
| --- | --- | --- |
| Web | Fly secrets (`fly secrets set DROPBOX_APP_KEY=... MSAL_CLIENT_ID=... -a marknotes-web`) | `DROPBOX_APP_KEY`, `MSAL_CLIENT_ID` |
| Desktop | `marknotes-electron` repository secrets, read at build time by `release.yml`; for `npm run dev`, set them as environment variables | `DROPBOX_APP_KEY`, `MSAL_CLIENT_ID` |

No signature hash is needed outside Android.

## 6. Proton Drive (all clients)

Proton Drive needs no app registration and no secrets on Android, web or desktop.

- **Login**: Proton SRP with the account password, plus a TOTP code when 2FA is enabled. CAPTCHA/human
  verification, two-password mode and FIDO2-only accounts aren't supported; the app shows a clear error
  for them.
- **Stored credentials**: the password is never stored. On Android the session tokens and the key passphrases
  that unlock the drive's keys are kept on the device, encrypted with a key held in the Android Keystore.
- **Every request** sends the header `x-pm-appversion: external-drive-marknotes@0.1.0-alpha`.
- **Login screen** shows "This is a third-party application not officially supported by Proton."
- **No Proton branding** (logos or similar) anywhere in the apps.

## Sync mutation safety

New synced files and planned folders must be created without replacing or adopting an existing entry. A name
conflict is a sync error, including when another device creates the entry after the listing.

Shared local case-only renames use kernel-enforced no-replace operations, not a destination existence check
followed by a potentially replacing move. Conflict copies are written privately with the losing edit time
(including epoch zero) and flushed before atomic no-replace publication. Durable recovery verifies and reuses
the same conflict-copy path and note ID after a crash; a racing edited destination is never overwritten.

OneDrive and Proton Drive folder deletion is deliberately unsupported: their recursive delete/trash operations
cannot atomically guarantee that a folder has no unseen children. Sync reports an error and keeps the folder's
records for a later retry; it never falls back to recursive deletion. Files can still be synced individually.
If OneDrive cannot store the supplied edit time after an upload, that step is reported as an error rather than
recording a successful sync with the wrong modification time.

OneDrive file and same-parent folder case-only renames use the stable item ID, the listing's eTag in
[`If-Match`](https://learn.microsoft.com/en-us/graph/api/driveitem-update?view=graph-rest-1.0#request-headers), and
[`@microsoft.graph.conflictBehavior=fail`](https://learn.microsoft.com/en-us/graph/api/resources/driveitem?view=graph-rest-1.0#instance-attributes):
a racing edit or name conflict fails without replacing another entry.
The same conditional update preserves the file's edit time; an unexpected identity or timestamp is an error.
Folder rename responses must preserve the folder kind and exact requested name, and cached descendants move
with the folder even when the next delta is empty. Folder timestamps are not stamped as file edit times.
Other OneDrive folder renames, moves, and Proton Drive remote renames report an unsupported-operation error
rather than moving entries without a conditional identity/content guarantee.

## Development sync source builds

When `libre/gradle/marknotes-sync-source.properties` exists, Gradle resolves the Kotlin bindings, host-native jar
and Android AAR from `libre/marknotes-sync/bindings/kotlin/build/libs`, not an unpublished GitHub release URL.
Initialize the submodules and, on Linux with the toolchain from
`libre/.github/actions/setup-sync-toolchain`, build the pinned source before running the Android Gradle commands:

```sh
git submodule update --init --recursive libre
libre/scripts/build-sync-from-source.sh
```

The shared script verifies the source commit and catalog version. CI performs these steps in
`.github/actions/setup-android-build`; no generated bindings or native binaries are committed here.
`-Pmarknotes.sync.fromSource=<checkout>` selects artifacts from an explicit source checkout instead.
Release validation rejects a development source pin: remove it only when the reviewed matching artifacts have
actually been published.

## 7. Release builds

`.github/workflows/release.yml` reads these repository secrets. If any are missing, the release APK shows the
matching provider as not configured.

| Secret | Value |
| --- | --- |
| `DROPBOX_APP_KEY` | Dropbox app key |
| `MSAL_CLIENT_ID` | Entra application (client) ID |
| `MSAL_RELEASE_SIGNATURE_HASH` | Base64 SHA-1 of the **release** keystore certificate |

1. Compute the release hash as in step 1, but use the release keystore and its alias:

   ```powershell
   keytool -list -v -keystore <release.keystore> -alias <alias> | Select-String "SHA1:"
   ```

2. Add a second Android redirect URI in Entra with the release hash (step 3.4).
3. Set the secrets:

   ```powershell
   gh secret set DROPBOX_APP_KEY --body "<app key>"
   gh secret set MSAL_CLIENT_ID --body "<client id>"
   gh secret set MSAL_RELEASE_SIGNATURE_HASH --body "<release hash>"
   ```
