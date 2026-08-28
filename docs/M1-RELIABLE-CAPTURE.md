# M1 — Reliable Capture

Implemented in this checkpoint:

- `BrainNotificationListener` using `NotificationListenerService` callbacks.
- MessagingStyle extraction with sender, message text, and message timestamp.
- Exact notification revision deduplication by notification key + normalized-content SHA-256.
- `BrainAccessibilityService` for event-driven foreground and accessible-text capture.
- 750 ms screen debounce, minimum useful text, SHA-256 + SimHash near-duplicate filtering.
- Password accessibility nodes are skipped before persistence.
- Active-window package is rechecked after debounce to prevent cross-app mislabeling.
- App sessions with one open foreground session at a time.
- Screen-off session closure.
- `UsageStatsManager` reconciliation fallback every 15 minutes through WorkManager.
- Foreground-session resolution prefers real application windows so IME/system-window events do not become fake app sessions.
- Master pause/resume enforced before persistence and observed by capture services.
- Quick Settings pause/resume tile.
- Per-app capture-policy repository with cloud AI OFF by default.
- Conservative notification + screen/OCR defaults for common authenticator/password-manager/banking-style package names.
- Capture-health state for service connection and last successful capture timestamps.
- Notification/screen/app-activity capture now writes both `capture_event` and normalized `memory` in one Room transaction.
- Minimal live Timeline and Capture Health UI for physical-device verification.

## M1 physical-device acceptance

1. Enable Notification Access, Accessibility, and Usage Access.
2. Post one test notification; exactly one memory should appear. Reposting unchanged content under the same notification key must not add another memory.
3. Open a text-heavy app and remain on a static page; screen memories must settle instead of repeating continuously.
4. Scroll enough to expose new content; a new screen memory should be retained.
5. Focus a password field; password-node text must never appear in stored screen memory.
6. Switch between two apps; the previous `app_session` closes and the new one opens.
7. Pause from Timeline, Settings, or the Quick Settings tile; no new automatic notification/screen/app-activity memories may be created while paused.
8. Resume and confirm capture continues.
9. Disable Accessibility or Notification Access and verify Capture Health reflects the missing access after returning to the app.

## Deliberately deferred to M2

- Screenshot OCR fallback execution.
- Voice recording/transcription.
- Gallery/share image OCR.
- Asset pipeline.
