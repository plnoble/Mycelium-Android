# Mycelium（知衍）安卓版 Update Architecture

## Goal

After the first manual installation, Mycelium（知衍）安卓版 should minimize manual update work.

Expected flow:

```text
Code pushed / release tag created
        |
GitHub Actions
        |
Build + test + sign APK
        |
GitHub Release
        |
Mycelium（知衍）安卓版 detects newer release
        |
Download APK
        |
Verify
        |
Launch Android package installer
        |
User confirms update
```

## Release source

Primary source: GitHub Releases for this repository unless a separate public release repository becomes necessary later.

Because this repository is currently public, the app can query public release metadata without embedding a private GitHub access token.

## Versioning

Semantic versions:

- v0.1.0 — feature milestone
- v0.1.1 — bug-fix release
- v0.2.0 — next feature milestone
- v1.0.0 — first stable release

Android must also maintain monotonically increasing `versionCode`.

## Release assets

A release should eventually contain:

- `mycelium-android-<version>.apk`
- checksum metadata
- release notes

The application model files must not be bundled into routine APK updates.

## App-side updater

Planned package:

```text
update/
├── UpdateManager
├── GitHubReleaseClient
├── VersionComparator
├── ApkDownloader
├── ApkVerifier
└── PackageInstallerLauncher
```

Updater responsibilities:

1. Query the latest stable release.
2. Parse release version metadata.
3. Compare against installed version.
4. Download the correct APK.
5. Verify checksum/digest where available.
6. Verify package identity/signing expectations.
7. Open the Android installer.
8. Preserve all app data, models, memories, and settings during normal upgrades.

## Check policy

Default behavior:

- check on app start only if enough time has passed since the last check
- periodic background check at a low frequency
- manual “Check for updates” button
- optional automatic APK download
- never repeatedly interrupt the user

## Signing

Every production APK must use the same signing key.

Signing material must never be committed to Git.

Expected CI secrets later:

- keystore material
- keystore password
- key alias
- key password

## Android limitation

For a normally sideloaded Android app, download and preparation can be automated, but Android generally still requires user confirmation to install an APK update.

Fully silent installation requires device-management/system-level privileges and is outside the normal Mycelium（知衍）安卓版 application model.

## Model updates

Application updates and model updates are separate systems.

```text
App update   -> APK / GitHub Releases
Model update -> Model Manager
Memory       -> persistent local database
```

Updating the APK must not delete or re-download the LLM model under normal circumstances.
