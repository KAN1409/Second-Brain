# Cortex Relay v1.0 — Single Update Acceptance Checklist

This file is the source of truth for the next device release.

## Release rule

Do **not** publish feature-by-feature device APKs for intermediate progress. Development may happen in several commits. Once implementation blockers **1–7** are code-complete and CI-green, produce one combined device candidate and use it for blocker **8**.

If the combined device test exposes a real blocker, keep it red, fix that blocker, and repeat the candidate only because acceptance failed — never because an individual sub-feature deserves its own APK.

Status legend:
- ✅ GREEN — implemented and validated enough for its current gate
- ❌ RED — incomplete or failed validation; blocks v1.0 acceptance

## Already green

- ✅ **Android capture works** — NotificationListener capture is running and stored events are visible in Relay diagnostics.
- ✅ **Cortex Local Bus V1 connectivity works** — `CORTEX_INGEST_V1`, connector `second_brain`, endpoint connected.
- ✅ **Per-event Cortex ACK correlation works** — Relay reports delivery only after Cortex ACKs the exact `event_id`; `Messenger.send()` alone is not treated as delivered.
- ✅ **Retry-safe V1 event identity works** — retries reuse the same wire event id and Cortex can return duplicate acceptance.
- ✅ **Recent Signals is human-readable and inspectable** — app/source, sender/title, preview, delivery state and tap-for-details exist.
- ✅ **Diagnostic accounting is explicit** — captured events, send attempts, ACKed deliveries, retry/failure incidents, lifecycle counters and per-signal delivery accounting are exported.
- ✅ **Update-in-place / permanent signer path works** — package remains `com.kareem.secondbrain`; permanent signer is preserved; installation uses `pm install -r`.

## Candidate 1 real-device result — FAILED acceptance, useful evidence retained

Candidate 1 (`versionCode 10`, `1.0.0-relay-v1-candidate`) proved several important paths on the real device:

- ✅ Relay → Cortex exact ACK path worked: diagnostic showed 6 captured, 6 send attempts, 6 correlated Cortex ACK deliveries, 0 rejection, 0 waiting and 0 retry incidents.
- ✅ Lifecycle observation worked on real notifications: POSTED / UPDATED / REMOVED counters moved and exact duplicate updates were suppressed.
- ✅ Stable notification/logical identities were visible across POSTED/UPDATED events.
- ❌ WhatsApp exposed a real duplicate-evidence case: Android posted both the useful child message notification and a group-summary/container notification at nearly the same time. Candidate 1 forwarded both, producing `HUMAN_MESSAGE` plus `OTHER` events for essentially the same underlying message.
- ❌ Candidate 1 diagnostic did not export normalized `source_profile_identity` / `conversation_identity`, so the two-WhatsApp-account acceptance condition could not be proven from the shared report alone.
- ⏳ Durable outbox process-death/reboot recovery was not tested yet because duplicate evidence must be fixed first.

Candidate 2 exists only because candidate 1 failed a real acceptance condition. Its remediation is general Android behavior, not a WhatsApp-specific text rule:

- Detect `Notification.FLAG_GROUP_SUMMARY`.
- Keep the summary locally for forensic evidence.
- Suppress only its Cortex delivery because Android child notifications carry the underlying evidence.
- Continue forwarding grouped child notifications normally.
- Export source-profile, notification-instance and conversation identities in diagnostics for account-scope validation.

## IMPLEMENTATION / ACCEPTANCE BLOCKERS

- ✅ **1. Durable outbox across process death / reboot — implementation ready, device recovery test pending under gate 8**
  - Undelivered delivery copies are persisted in a disk-backed outbox before send.
  - Pending entries are restored on process startup, boot/package-replace hooks and NotificationListener startup.
  - Entries are retired only after correlated Cortex ACK or explicit terminal rejection.
  - Retry/recovery preserves the same stored event id and `sb_<eventId>` wire id.
  - Diagnostics expose exact `restored_pending_event_ids` for device acceptance.

- ✅ **2. Notification lifecycle tracking — implementation + basic real-device behavior validated**
  - Durable per-notification state tracks `POSTED → UPDATED → REMOVED` using stable Android/source identity.
  - Updates retain generation and sequence instead of becoming unrelated snapshots by default.
  - A removal closes the current notification instance; a later repost starts a new generation.

- ❌ **3. Meaningful delta / duplicate evidence — candidate 2 remediation awaiting CI + device revalidation**
  - Exact repeated snapshots are suppressed as duplicates.
  - MessagingStyle updates identify genuinely new structured messages and send only their evidence delta.
  - Deterministic percentage/progress-only machine churn is retained locally for forensics but suppressed from Cortex delivery.
  - Android `FLAG_GROUP_SUMMARY` containers are now retained locally but suppressed from Cortex delivery; grouped child notifications remain forwardable.
  - This stays RED until a real WhatsApp message produces one useful Cortex-delivered child signal while its summary/container is filtered locally.

- ✅ **4. Multi-account / conversation identity foundation — implementation ready; real-device proof pending gate 8**
  - Source identity includes package + Android user/profile + UID rather than package name alone.
  - Notification/conversation identity uses notification key, shortcut/conversation metadata and Person/participant evidence when available.
  - Tests verify distinct Android profile/UID scope does not collapse into one source identity.
  - Candidate 2 diagnostics export `source_profile_identity`, `notification_instance_identity`, `conversation_identity` and `conversation_identity_basis` so the user's two-WhatsApp-account scenario can be measured directly.

- ✅ **5. Rich evidence normalization + provenance**
  - Full MessagingStyle structures, sender/conversation/timestamps, replyability, Person and Android metadata are captured.
  - Deterministic extraction covers visible URLs, phone numbers, OTPs, explicit dates/times, money values, references/tracking/order-like identifiers and shown person names.
  - Every entity carries source field + exact span + confidence.
  - New-message delta classification/entities use only the new evidence, preventing stale older messages from contaminating the new signal.
  - Compact oversized payload handling preserves Relay normalization/provenance metadata instead of dropping it.

- ✅ **6. Stable logical signal identity**
  - One notification instance keeps one logical signal identity across ordinary lifecycle/content updates; `update_sequence` distinguishes revisions.
  - A genuine new MessagingStyle message delta gets a distinct logical signal identity.
  - Retry/replay remains anchored to the same captured `event_id` / V1 wire id.
  - No breaking `CORTEX_SIGNAL_V2` change was introduced; new identity/lifecycle evidence is carried inside compatible metadata.

- ✅ **7. Conservative signal/noise classification foundation**
  - Signal type classification covers human_message, email, call, sms, otp, banking, delivery, calendar, security, download, system_noise and other.
  - `FORWARD / LOW_VALUE / DROP_CONFIRMED_NOISE` remains conservative.
  - Confirmed Android container/control surfaces and deterministic machine churn can be suppressed while uncertain evidence is preserved.
  - Personal priority/relevance remains Cortex's responsibility.

## DEVICE ACCEPTANCE GATE — candidate 2 must pass as one combined test

- ❌ **8. One combined real-device acceptance test**
  - Cortex available: real WhatsApp message → captured → exactly one useful child delivery → exact ACK / Cortex signal id; Android group summary remains local/filtered.
  - Cortex unavailable: real message → captured → persists in durable outbox.
  - Kill/restart Relay (and reboot if practical) while Cortex unavailable → pending signal remains and the same restored event id is reported.
  - Restore Cortex → same pending signal delivers once logically and receives correlated ACK.
  - Updated notification does not create unrelated duplicate evidence.
  - New MessagingStyle message inside an updated notification is preserved as new evidence.
  - Two WhatsApp accounts remain distinguishable where Android evidence permits; compare exported `source_profile_identity` and conversation identity evidence. If Android exposes no account discriminator, Relay must say the evidence is insufficient rather than invent an account identity.
  - Confirmed media/progress/container noise remains local and does not consume Cortex delivery.
  - Diagnostic report shows zero unexplained backlog/rejections and explains any retries per signal.

## Explicitly not required before v1.0

These are valuable later but must not distract from the acceptance gate:

- Cortex → Relay capture-policy feedback loop.
- Full breaking `CORTEX_SIGNAL_V2` rollout.
- Long-term personal memory or reasoning inside Relay (prohibited by product boundary).
- Cosmetic app-specific filtering work that does not improve the general evidence gateway.

## Product invariant

**Relay captures, normalizes, preserves provenance, filters only confirmed noise, and delivers reliably. Cortex remains the brain: understanding, memory, reasoning, prioritization, suggestions and actions.**
