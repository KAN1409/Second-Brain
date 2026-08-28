# M2 — Multimodal Brain

Status: implementation branch `m2-multimodal-brain`.

## Asset storage

- Imported binary content is copied into app-private `files/assets/` storage.
- Asset identity is SHA-256 of the bytes.
- Existing SHA-256 rows/files are reused instead of duplicating the asset.
- Memory-to-asset provenance is stored in `memory_asset`.
- Assets keep their 90-day expiry independently so retention/GC can be implemented later without Room BLOBs.

## Voice memories

Voice capture is explicit and user initiated only.

1. User presses Start recording.
2. `VoiceRecordingService` starts as a microphone foreground service.
3. Audio is recorded as mono, 16 kHz, PCM16 WAV.
4. On Stop, the WAV is imported into content-addressed private asset storage.
5. A VOICE capture event + VOICE_TRANSCRIPT memory are persisted immediately.
6. `TranscriptionWorker` runs asynchronously and updates the same event/memory.
7. Transcription failure marks processing FAILED but does not delete the WAV.

The current M2 adapter uses `dev.ffmpegkit-maintained:whisper-android:1.0.0`, which wraps whisper.cpp for local file transcription on arm64-v8a.

Development model locations:

```
files/models/whisper/ggml-base.bin
files/models/whisper/ggml-tiny.bin
```

M2 intentionally does not embed model binaries in Git. Model download/checksum lifecycle remains part of the Model Manager hardening phase. The current free AAR path uses unquantized models; the SPEC q5_1 optimization remains scheduled for the direct whisper.cpp native integration/resource-management phase.

## Images and OCR

Images can enter through:

- the in-app image picker;
- Android ACTION_SEND/ACTION_SEND_MULTIPLE;
- Accessibility screenshot fallback.

Persistent image imports:

1. Import bytes into AssetRepository.
2. Reuse an existing asset for an identical SHA-256.
3. Ignore a repeated IMAGE capture whose asset/content hash was already captured.
4. Persist IMAGE + OCR memory immediately.
5. Run OCR asynchronously.
6. Update the same memory body with recognized text.

OCR stack:

- Primary: bundled ML Kit Latin text recognition.
- Optional Arabic fallback: Tesseract4Android with `ara.traineddata`.

Development Tesseract location:

```
files/models/tesseract/tessdata/ara.traineddata
files/models/tesseract/tessdata/eng.traineddata   # optional
```

## Accessibility screenshot fallback

Screenshot OCR is fallback-only when the accessibility tree has too little useful text.

- Requires the app policy `accessibility=true` and `ocr=true`.
- Never runs when password nodes were detected in that traversal.
- Throttled to at most one automatic screenshot request per app per 5 seconds.
- Screenshot bitmap is written only to app cache as a temporary PNG.
- The worker re-checks capture mode and the current per-app policy before OCR.
- The temporary PNG is deleted whether OCR succeeds, fails, or is no longer allowed.
- Only OCR text is eligible for persistence; the screenshot itself is not promoted to a persistent Asset.

Android secure/protected windows are never bypassed.

## Failure semantics

Capture and asset persistence precede enrichment. Whisper/OCR failures can change processing state to FAILED but cannot remove the underlying user-saved asset.

## Still outside M2

- Model download/update UI and checksum lifecycle.
- Transcript editing UI / full Memory Detail editor.
- Search indexing/chunking/embeddings (M3).
- Retention garbage collection and long-term consolidation.
- Thermal inference gate and model selection policy hardening.
