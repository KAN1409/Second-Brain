# Second Brain Android

Local-first Android second-brain project implementing SPEC-1 / SPEC-1.1.

Current implementation branch: **M2 Multimodal Brain** (`0.3.0-m2-dev`).

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
- Content-addressed private asset storage with SHA-256 deduplication.
- Explicit foreground voice recording + asynchronous local whisper.cpp transcription adapter.
- Image/file Share + picker ingestion and ML Kit/Tesseract OCR pipeline.
- Accessibility screenshot OCR fallback with temporary-image deletion.

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

See `docs/SPEC-1.1-DECISIONS.md`, `docs/ARCHITECTURE.md`, `docs/M1-RELIABLE-CAPTURE.md`, and `docs/M2-MULTIMODAL-BRAIN.md`.
