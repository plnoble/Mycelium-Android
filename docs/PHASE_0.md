> 更名说明：本文件为历史记录（原名 Nivra，2026-09-29 更名为 Mycelium（知衍）安卓版）。正文未改。

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
- Complete Gradle Wrapper with a pinned distribution checksum
- Unit coverage for missing releases, release parsing, HTTP/network failures,
  version decisions and coroutine cancellation
- Android Lint and verification report artifacts in CI
- Robolectric/Compose smoke tests for activity startup, the no-release message
  and retry after network failure (Android API 35)

## Current infrastructure scope

This iteration validates the debug APK, the Phase 0 screen and GitHub release
checks. Keep package `com.nivra.app`, versionName `0.0.1` and versionCode `1`.
Character, Memory, LLM and ASR/TTS implementation is deferred.

`UpdateManager` coordinates update decisions through the replaceable
`ReleaseSource` interface. `GitHubReleaseClient` handles public GitHub metadata
without credentials. HTTP 404 maps to `NoPublishedRelease`; other HTTP and
network failures map to a displayable error. Cancellation propagates to the
screen's coroutine. Download, checksum verification and installation remain
future responsibilities of the updater; the current browser handoff is retained.

### Local verification

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Unit report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint report: `app/build/reports/lint-results-debug.html`
- Manual smoke check: launch Nivra, confirm the Phase 0/version labels, tap
  **Check GitHub for updates**, and confirm the no-release message when GitHub
  has no release. Repeat the check to confirm the button is re-enabled.

Never commit local SDK paths, build outputs, signing material or model files.

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
