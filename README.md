# Nivra

Nivra is a local-first Android AI companion focused on long-term character consistency, memory, voice conversation, and relationship continuity.

## Product principles

- Local-first
- Character-first
- Memory-first
- Model-replaceable
- Voice-native
- Long-term consistency

## Initial target

- Platform: Android
- Primary test device: RedMagic 11 Pro+
- Local LLM: Qwen3.5-4B GGUF (initial target)
- Text chat first, then local voice
- One character in the first usable version
- No 3D/avatar system in the initial phases

## Core architecture

```text
Text / Voice UI
      |
Conversation Engine
      |
+-----+------------------+
|     |                  |
Character Engine    Memory Engine
|                    |
Relationship Engine |
|                    |
Mood / State Engine |
+---------+----------+
          |
    Prompt Builder
          |
      LLM Engine
          |
      llama.cpp
```

## Roadmap

### Phase 0 — Foundation
- Android project skeleton
- Local model loading
- Streaming text generation
- Versioning and release infrastructure
- GitHub-based update architecture

### Phase 1 — Text Companion
- Chat UI
- Character profile
- Conversation persistence
- Basic settings

### Phase 2 — Memory
- Memory extraction
- Memory retrieval
- Importance scoring
- Deduplication
- User-editable memory

### Phase 3 — Relationship
- Relationship state
- Long-term character continuity
- Character self-memory
- Reflection pipeline

### Phase 4 — Voice
- Local ASR
- Local TTS
- VAD
- Streaming voice
- Interruption / barge-in

### Phase 5 — Proactive Companion
- Time awareness
- Follow-up on unresolved topics
- Carefully rate-limited proactive messages

## Updating

Nivra will use GitHub Releases as its primary release channel.

The Android app will:
1. Check the latest compatible GitHub Release.
2. Compare versionCode/versionName.
3. Download the signed APK.
4. Verify integrity and package signature.
5. Ask Android to install the update.

Android normally still requires a final user confirmation for sideloaded APK installation; the rest of the flow can be automated.

See [docs/UPDATE_ARCHITECTURE.md](docs/UPDATE_ARCHITECTURE.md).

## Status

Planning / Phase 0.

## Build and verify Phase 0

Install JDK 17 or a compatible newer JDK and the Android SDK (API 37).
Set `JAVA_HOME` and `ANDROID_HOME`, or configure `sdk.dir` in an untracked
`local.properties`. Android Studio's bundled JDK can also be used.

Use the checked-in Gradle Wrapper; no global Gradle installation is required:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Linux/macOS, use `./gradlew` with the same tasks. The wrapper pins Gradle
9.5.0 and verifies the distribution's SHA-256 checksum.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Open the app to see **Nivra / Phase 0 · Foundation**, then tap
**Check GitHub for updates**. If the repository has no published release,
the app displays **No GitHub Release has been published yet.**

Android CI builds this APK, runs the unit tests and Android Lint, and uploads
the APK and verification reports. Release tags use the same wrapper and
require the signing secrets described in [docs/PHASE_0.md](docs/PHASE_0.md).
