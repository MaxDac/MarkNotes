# Configuring Dropbox and OneDrive

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

## 5. Release builds

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
