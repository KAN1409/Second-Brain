# M1 Validation Report

Checkpoint: M1 Reliable Capture  
Date: 2026-08-28

## Passed in preparation environment

- XML parse: all project XML files valid.
- Version catalog TOML parse: valid.
- Module graph: 21 included modules, no missing module build files.
- Kotlin source/package path consistency: no mismatches found.
- Architecture guards:
  - no `feature -> core.database` imports
  - no `feature -> ai.gemini` imports
  - no `domain -> Android framework` imports
  - no `capture:android -> Room DAO/database` imports
- Pure Kotlin compile/runtime smoke test passed for:
  - Unicode/whitespace normalization
  - SHA-256
  - SimHash/exact screen duplicate behavior
  - normal-app capture defaults
  - sensitive banking/authenticator-style default content blocking
- Capture services fail closed until persisted master capture state is observed.
- Sensitive package defaults: notifications OFF, accessibility OFF, OCR OFF, cloud AI OFF; usage timing remains ON.
- Foreground app resolution prefers application windows to avoid IME/system-window session pollution.
- UsageStats reconciliation evaluates resumed/paused state per package instead of blindly trusting the final raw event.
- Capture-health timestamps are monotonic and will not move backwards when reconciliation processes an older event.

## Android build status

A full `./gradlew test :app:assembleDebug` could not be executed in this preparation container because it cannot resolve `services.gradle.org` (DNS failure). This is an environment/network limitation, not a known source compilation failure.

Authoritative Android compile verification remains:

```bash
./gradlew test :app:assembleDebug
```

on JDK 17 with Android SDK 37 and network access, or the included GitHub Actions workflow.

## Deliberate tool pin

KSP is pinned to `2.3.9` for M1 rather than `2.3.10` because 2.3.10 currently has open regressions in Android/KSP2 test processing and some incremental/type-resolution paths. Re-evaluate on a later patch.

## Physical-device release blockers for M1

- automatic capture while paused = 0
- password-field persistence = 0
- explicitly blocked per-app source persistence = 0
- sensitive-app default notification capture = OFF
- unchanged notification callback duplicates = 0 for same key + same normalized content
- static screen capture must settle rather than continuously duplicate
- app session transitions must not identify IME/system windows as the foreground application
