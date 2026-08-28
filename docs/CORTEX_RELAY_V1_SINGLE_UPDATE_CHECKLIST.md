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

## Candidate history

### Candidate 1 — FAILED acceptance, useful evidence retained

Candidate 1 (`versionCode 10`, `1.0.0-relay-v1-candidate`) proved several important paths on the real device:

- ✅ Relay → Cortex exact ACK path worked: diagnostic showed 6 captured, 6 send attempts, 6 correlated Cortex ACK deliveries, 0 rejection, 0 waiting and 0 retry incidents.
- ✅ Lifecycle observation worked on real notifications: POSTED / UPDATED / REMOVED counters moved and exact duplicate updates were suppressed.
- ✅ Stable notification/logical identities were visible across POSTED/UPDATED events.
- ❌ WhatsApp exposed a real duplicate-evidence case: Android posted both the useful child message notification and a group-summary/container notification at nearly the same time. Candidate 1 forwarded both.
- ❌ Candidate 1 diagnostic did not export normalized source/profile and conversation identities.

### Candidate 2 — duplicate/account proof PASSED; message classification defect found

Candidate 2 (`versionCode 11`, `1.0.0-relay-v1-candidate2`) was installed and tested on the real device.

- ✅ Diagnostic showed 4 captured events split deterministically into 2 Cortex deliveries + 2 local filtered Android group summaries.
- ✅ Both delivered child notifications received correlated Cortex ACKs.
- ✅ Group-summary containers had `DROP_CONFIRMED_NOISE`, zero send attempts and zero Cortex signal IDs.
- ✅ Zero rejection, zero waiting/in-flight and zero retry/delivery incidents.
- ✅ Two WhatsApp account/profile surfaces were distinguishable from Android evidence: the two message pairs had different `source_profile_identity` values and different conversation identities, each based on Android `shortcutId`.
- ❌ The useful forwarded child notification was classified `OTHER` while the filtered group summary was classified `HUMAN_MESSAGE`.

### Candidate 3 — notification evidence acceptance PASSED

Candidate 3 (`versionCode 12`, `1.0.0-relay-v1-candidate3`) was installed and tested on the real device after generalizing Android conversation classification.

- ✅ Real-device diagnostic showed 4 captured WhatsApp evidence rows split into exactly 2 Cortex deliveries + 2 local filtered Android group summaries.
- ✅ Both useful forwarded child notifications were classified `HUMAN_MESSAGE`.
- ✅ Both delivered child notifications received correlated Cortex ACKs (`signal 7958` and `signal 7961`).
- ✅ Matching group-summary/container rows were classified as confirmed noise for delivery purposes, retained locally, had zero send attempts and zero Cortex signal IDs.
- ✅ Zero rejection, zero waiting/in-flight and zero retry/delivery incidents.
- ✅ The two WhatsApp account/profile surfaces remained distinguishable: `source-profile_71b76bc2...` vs `source-profile_c8b7171f...`, with different conversation identities grounded in Android `shortcutId`.
- ✅ No WhatsApp-specific text rule was added; the delivered child is recognized as a human message using structured/replyable Android conversation evidence.

### Candidate 3 — durable outbox end-to-end recovery PASSED

Real-device durable-outbox acceptance was repeated with the corrected helper so the recovered Relay process was not force-stopped after Cortex restoration.

- ✅ A real WhatsApp event remained pending while Cortex was unavailable.
- ✅ Relay process was killed and restarted while Cortex remained unavailable.
- ✅ Before restoring Cortex, diagnostic reported `waiting_or_in_flight = 1` and restored exact pending wire event `sb_aaaa933a-3f78-405c-b07b-2a7a8caf5bd2` from durable storage.
- ✅ That recovered process showed `captured = 0`, `send_attempts = 0`, `delivered_cortex_ack = 0`, proving the item was recovered rather than recaptured.
- ✅ Cortex bind failures while disabled left the durable item retained; the pre-restore diagnostic recorded 10 retry/delivery incidents and the explicit retained-event bind-failure reason.
- ✅ After Cortex was restored, the **same recovered Relay process** sent exactly one ingest attempt and received exactly one correlated Cortex ACK.
- ✅ Final diagnostic reported `connection_state = CONNECTED`, `send_attempts = 1`, `delivered_cortex_ack = 1`, `rejected_by_cortex = 0`, and `waiting_or_in_flight = 0`.
- ✅ Cortex accepted the recovered event as signal `7972`.
- ✅ No new Relay capture occurred during completion (`captured = 0`), so recovery completion did not create a second logical evidence row in Relay.
- ✅ Retry accounting is explained: repeated bind/retry incidents occurred only while Cortex was intentionally disabled; once Cortex returned, one send completed with one ACK and zero backlog.

## IMPLEMENTATION / ACCEPTANCE BLOCKERS

- ✅ **1. Durable outbox across process death / reboot — end-to-end process-death recovery and final delivery proven**
  - Undelivered delivery copies are persisted in a disk-backed outbox before send.
  - Pending entries are restored on process startup, boot/package-replace hooks and NotificationListener startup.
  - Entries are retired only after correlated Cortex ACK or explicit terminal rejection.
  - Retry/recovery preserves the same stored event id and `sb_<eventId>` wire id.
  - Real-device process-death acceptance restored exact event `sb_aaaa933a-3f78-405c-b07b-2a7a8caf5bd2` while Cortex remained disabled, then delivered it after Cortex returned with correlated ACK / Cortex signal `7972`.
  - Final recovered-process diagnostic showed one send attempt, one ACK, zero rejection and zero waiting backlog.
  - Diagnostics expose exact `restored_pending_event_ids` for device acceptance.

- ✅ **2. Notification lifecycle tracking — implementation + basic real-device behavior validated**
  - Durable per-notification state tracks `POSTED → UPDATED → REMOVED` using stable Android/source identity.
  - Updates retain generation and sequence instead of becoming unrelated snapshots by default.
  - A removal closes the current notification instance; a later repost starts a new generation.

- ✅ **3. Meaningful delta / duplicate evidence — real-device duplicate/classification path PASSED**
  - ✅ Exact repeated snapshots are suppressed as duplicates.
  - ✅ Android `FLAG_GROUP_SUMMARY` containers are retained locally but suppressed from Cortex delivery while grouped child notifications remain forwardable.
  - ✅ Candidate 3 real-device test produced exactly one Cortex-delivered `HUMAN_MESSAGE` child per tested WhatsApp message while the matching summary stayed local.
  - ✅ MessagingStyle updates identify genuinely new structured messages and send only their evidence delta in implementation tests.
  - ✅ Deterministic percentage/progress-only machine churn is retained locally for forensics but suppressed from Cortex delivery.

- ✅ **4. Multi-account / conversation identity — real-device proof PASSED**
  - Source identity includes package + Android user/profile + UID rather than package name alone.
  - Notification/conversation identity uses notification key, shortcut/conversation metadata and Person/participant evidence when available.
  - Candidate 3 real-device diagnostic showed different `source_profile_identity` and conversation identities for the two WhatsApp account/profile surfaces.
  - Both conversation identities were grounded in Android `shortcutId`; Relay did not invent an account label.

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

## DEVICE ACCEPTANCE GATE — candidate 3 is current

- ❌ **8. One combined real-device acceptance test**
  - ✅ Cortex available: real WhatsApp messages → exactly one useful `HUMAN_MESSAGE` child delivery each → correlated ACK / Cortex signal id; Android group summaries stayed local/filtered.
  - ✅ Cortex unavailable: real message persisted in durable outbox and remained waiting.
  - ✅ Kill/restart Relay while Cortex unavailable: pending signal survived and exact restored event `sb_aaaa933a-3f78-405c-b07b-2a7a8caf5bd2` was reported.
  - ✅ Restore Cortex → same recovered pending signal delivered with one ingest attempt, one correlated ACK, Cortex signal `7972`, and waiting returned to zero.
  - ✅ Updated/summary notification behavior tested so far does not create duplicate Cortex evidence for the WhatsApp cases exercised.
  - ❌ New MessagingStyle message inside an already-live updated notification still needs explicit real-device delta proof.
  - ✅ Two WhatsApp account/profile surfaces are proven distinguishable where Android evidence permits.
  - ✅ Confirmed Android group-summary/container noise remains local and does not consume Cortex delivery.
  - ✅ Final recovery diagnostic shows zero unexplained backlog/rejections; retry incidents are explained by Cortex being intentionally disabled during the test.

## Explicitly not required before v1.0

These are valuable later but must not distract from the acceptance gate:

- Cortex → Relay capture-policy feedback loop.
- Full breaking `CORTEX_SIGNAL_V2` rollout.
- Long-term personal memory or reasoning inside Relay (prohibited by product boundary).
- Cosmetic app-specific filtering work that does not improve the general evidence gateway.

## Product invariant

**Relay captures, normalizes, preserves provenance, filters only confirmed noise, and delivers reliably. Cortex remains the brain: understanding, memory, reasoning, prioritization, suggestions and actions.**
