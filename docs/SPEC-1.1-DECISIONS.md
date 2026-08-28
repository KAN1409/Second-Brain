# SPEC-1.1 implementation decisions

1. AGP 9 built-in Kotlin is used; `org.jetbrains.kotlin.android` is intentionally not applied.
2. KSP2 is used for Room3 and Hilt processing.
3. Room remains the source of truth. AppSearch is rebuildable.
4. Embeddings are persisted in Room (`memory_embedding`) so rebuilding AppSearch does not require re-embedding history.
5. Capture commands include notification, screen, app activity, voice, image, share, note, link and file.
6. Long-term memories can reference immutable evidence stubs so provenance survives raw retention expiry.
7. Automatic screenshot OCR is policy-gated and is not a method for bypassing protected/secure windows.
8. Voice recording is always explicitly user initiated.
9. Cloud upload remains disabled by default per app.
10. AI confidence is not trusted as a model self-report; grounding is validated deterministically.

11. Room3 requires an explicit `SQLiteDriver`; Android uses `AndroidSQLiteDriver` from `androidx.sqlite:sqlite-framework` 2.7.0.

## Build-tool hardening

- KSP is pinned to `2.3.9` for this checkpoint rather than `2.3.10`. The newer release has open Android/KSP2 regressions affecting test processing and some incremental/type-resolution paths under AGP 9/Kotlin 2.3.x. Re-evaluate on a later KSP patch instead of automatically taking latest.
- Capture services fail closed on process/service startup (`captureRunning = false`) until persisted capture state is observed; the repository independently enforces master pause before every write.
- Sensitive authenticator/password-manager/banking-style packages default to notifications OFF, accessibility OFF, OCR OFF, AI upload OFF; usage-session tracking remains allowed because it records only app identity/timing.
