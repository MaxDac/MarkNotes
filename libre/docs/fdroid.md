# Publishing on F-Droid

MarkNotes (libre) is published in the main [F-Droid](https://f-droid.org) repository as
`io.github.maxdac.marknotes.libre`. F-Droid builds every release from source on its own servers; because the build
is reproducible, it then publishes **our** signed APK from GitHub Releases (`Binaries:` in the recipe) after
checking that its own build matches it. The same APK is on F-Droid and GitHub, signed with the same key.

## Files

| File | Purpose |
| --- | --- |
| `.fdroid.yml` | The build recipe, in `fdroid rewritemeta` format so it can be copied to fdroiddata unchanged. |
| `fdroid/srclibs/marknotes-sync.yml` | The srclib for [marknotes-sync](https://github.com/MaxDac/marknotes-sync), built from source by `scripts/build-sync-from-source.sh`. |
| `fastlane/metadata/android/en-US/` | Store listing: title, descriptions, icon, screenshots and one changelog per versionCode. F-Droid reads it from the tagged commit. |
| `scripts/fdroid-lint.sh` | Runs `fdroid lint` and `fdroid rewritemeta` against a minimal fdroiddata checkout and checks the recipe matches the app and marknotes-sync versions. |

## First submission (once)

1. Release `v0.1.0` from this repo (README, "Releasing"), so the tag and `marknotes-libre-0.1.0.apk` exist.
2. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata) on GitLab and add:
   - `metadata/io.github.maxdac.marknotes.libre.yml`: a copy of `.fdroid.yml`;
   - `srclibs/marknotes-sync.yml`: a copy of `fdroid/srclibs/marknotes-sync.yml`.
3. Check locally with fdroidserver (`pip install fdroidserver`) in the fdroiddata checkout:
   `fdroid lint io.github.maxdac.marknotes.libre`, `fdroid rewritemeta io.github.maxdac.marknotes.libre` (no diff),
   and, with the [fdroidserver build setup](https://f-droid.org/docs/Installing_the_Server_and_Repo_Tools/),
   `fdroid build -v -l io.github.maxdac.marknotes.libre`.
4. Open a merge request using the *App inclusion* template. Mention that the build is reproducible (signed APK
   in `Binaries:`, signer `AllowedAPKSigningKeys`), that Dropbox is the only network service (*NonFreeNet*), and
   that marknotes-sync is built from source through the new srclib.
5. Reviewers may ask for changes; apply them to `.fdroid.yml` here too, so the two stay identical.

After the merge, F-Droid builds the app within a few days and it appears in the client after the next index update.

## New releases

Nothing to do on F-Droid: the recipe has `AutoUpdateMode: Version` and `UpdateCheckMode: Tags`, so F-Droid's
checkupdates bot notices the new `vX.Y.Z` tag, copies the last build entry with the new version and commit, and
builds it. It reads `versionName`/`versionCode` from `app/build.gradle.kts`, which is why they are literals.

Update fdroiddata by hand (a small MR) when the build itself changes:

- **marknotes-sync version**: the `marknotes-sync@vX.Y.Z` srclib reference (the bot doesn't update srclibs);
- the Rust toolchain, cargo-ndk or NDK pins, or the build commands;
- the signing key (avoid: users would have to reinstall).

`scripts/fdroid-lint.sh` fails in CI when `.fdroid.yml` drifts from the app or marknotes-sync version, which is a
reminder to send the same change to fdroiddata.

## Reproducibility

F-Droid compares its unsigned build with our signed APK using `apksigcopier compare`. Anything that differs between
builds breaks this and F-Droid then stops publishing updates. What keeps the build reproducible:

- the native library: a pinned Rust toolchain (`1.99.1`), cargo-ndk (`4.1.2`) and NDK (`r27d`, `27.3.13750724`),
  with `--remap-path-prefix` for the source and cargo paths, no build ID, and `SOURCE_DATE_EPOCH`
  (`scripts/build-sync-from-source.sh`);
- the APK: no VCS info (`vcsInfo.include = false`) and no dependency metadata block (`dependenciesInfo`), so
  nothing depends on the commit, path or machine.

Two CI checks guard it: **Checks / Reproducible build** rebuilds on a second runner at another path and in another
time zone and requires identical APKs, and the Release workflow runs `apksigcopier compare` between the signed
release APK and the Checks' F-Droid build before publishing.
