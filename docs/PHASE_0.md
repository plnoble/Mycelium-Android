# Phase 0 — Foundation

## Objective

Produce the first installable Nivra Android build and establish a repeatable GitHub release/update pipeline.

## Current implementation

- Android application module
- Kotlin + Jetpack Compose
- Package: `com.nivra.app`
- Version: `0.0.1` / versionCode 1
- GitHub Release check from inside the app
- CI workflow for debug builds
- Release workflow for signed APKs
- Model files excluded from Git
- Signing secrets excluded from Git

## Remaining Phase 0 work

1. Make CI green.
2. Create one permanent Android release signing key.
3. Add the signing key and passwords to GitHub Actions Secrets.
4. Create tag `v0.0.1`.
5. Confirm GitHub creates the signed Release APK.
6. Install `v0.0.1` on the RedMagic.
7. Publish `v0.0.2` and verify the app detects it.
8. Replace the temporary browser-based APK handoff with in-app download, checksum validation and package-installer launch.
9. Add the replaceable `LlmEngine` interface and local model manager skeleton.

## Signing secrets

The release workflow expects:

- `NIVRA_KEYSTORE_BASE64`
- `NIVRA_KEYSTORE_PASSWORD`
- `NIVRA_KEY_ALIAS`
- `NIVRA_KEY_PASSWORD`

Never commit the keystore or passwords to the repository.

## Acceptance criteria

Phase 0 is complete when:

- CI builds successfully.
- A signed APK is produced by GitHub Actions.
- The APK installs on the target Android device.
- A later GitHub Release is detected by the installed app.
- Updating does not erase application data.
