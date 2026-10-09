# Mycelium（知衍）安卓版 Product Specification

## 1. Product definition

Mycelium（知衍）安卓版 is an Android-native, local-first AI companion.

The initial product is not a generic assistant and not a 3D virtual character. Its purpose is to create the feeling of continuity with one persistent digital character through:

- stable personality
- long-term memory
- relationship continuity
- text conversation
- voice conversation
- local inference

## 2. Initial scope

### In scope
- Android native application
- One companion character
- Local LLM inference
- Text conversation
- Conversation history
- Character profile
- Memory extraction and retrieval
- Relationship state
- Local ASR/TTS in later phases
- GitHub-based application updates

### Out of scope for early versions
- 3D avatars
- Live2D
- multi-agent systems
- phone automation
- smart-home control
- web search
- MCP
- multi-user accounts
- cloud sync
- character marketplace

## 3. Character model

Character state must be separated into:

1. Core identity — mostly immutable
2. Core traits — slowly changing or fixed
3. Long-term relationship state
4. Temporary mood/state
5. Character memories
6. User memories

The character must remain independent from the underlying LLM. Changing the model must not replace the character.

## 4. Memory model

Initial memory categories:

- user_fact
- preference
- episode
- relationship
- character_memory

Memory records should include at minimum:

- id
- type
- content
- importance
- confidence
- created_at
- last_accessed_at
- access_count
- source_message_ids
- embedding reference / vector data

Raw conversation and derived memories must remain distinct.

Derived inference/reflection must never be stored as confirmed fact without provenance.

## 5. Relationship model

Internal relationship state may contain:

- familiarity
- trust
- closeness
- playfulness
- openness
- conflict
- interaction_frequency
- days_known

These values are internal behavioral signals, not a gamified score shown to the user by default.

## 6. Initial technical direction

- Kotlin
- Jetpack Compose
- Room / SQLite
- llama.cpp for GGUF inference
- replaceable LLM engine interface
- local speech stack to be selected in Phase 4

## 7. First meaningful acceptance test

Mycelium（知衍）安卓版 should be able to remember an important detail from an earlier conversation and recall it naturally days later when contextually relevant, without the user explicitly asking it to remember.

The goal is not perfect recall. The goal is contextual continuity.
