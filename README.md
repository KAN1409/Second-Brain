# Second Brain Android

Local-first Android second-brain project implementing SPEC-1 / SPEC-1.1.

Current checkpoint: **M1 Reliable Capture** (`0.2.0-m1-dev`).

## Implemented

- Multi-module AGP 9.3.2 / API 37 project foundation (KSP 2.3.9 pinned deliberately for M1).
- Room3 source-of-truth schema.
- Deterministic normalization, SHA-256, SimHash, and screen deduplication.
- Atomic capture-event + normalized-memory persistence.
- Notification capture including MessagingStyle evidence.
- Accessibility-visible screen-text capture with password-node filtering.
- Foreground app sessions and UsageStats reconciliation fallback.
- Master pause/resume and Quick Settings tile.
- Per-app policy storage and conservative sensitive-app defaults (notification + screen/OCR blocked by default).
- Capture Health diagnostics.
- Minimal live Timeline for M1 verification.

## Build

Requirements:

- JDK 17
- Android SDK 37
- Internet access for first dependency resolution

```bash
./gradlew test :app:assembleDebug
```

The project bootstrap script pins Gradle 9.5.0 and verifies its distribution SHA-256.

## Architecture invariant

> Capture reliably. Store locally. Understand asynchronously. Retrieve deterministically. Generate only from evidence.

Additional invariant:

> No personal claim may outlive its evidence.

See `docs/SPEC-1.1-DECISIONS.md`, `docs/ARCHITECTURE.md`, and `docs/M1-RELIABLE-CAPTURE.md`.
