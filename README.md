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
