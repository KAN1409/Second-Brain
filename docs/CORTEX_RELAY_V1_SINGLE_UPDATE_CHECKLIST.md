# Cortex Relay v1.0 — Final Acceptance Checklist

This file is the release source of truth for Cortex Relay v1.0.

## FINAL RELEASE STATUS

- ✅ **ALL IMPLEMENTATION / ACCEPTANCE BLOCKERS 1–8 ARE GREEN.**
- ✅ Candidate 3 (`versionCode 12`, `1.0.0-relay-v1-candidate3`) passed the combined real-device acceptance gate.
- ✅ Final release identity is `versionCode 13`, `versionName 1.0.0`.
- ✅ Package remains `com.kareem.secondbrain` for update-in-place compatibility.
- ✅ Local Bus wire contract remains `CORTEX_INGEST_V1` with `connector_id = second_brain`.
- ✅ Permanent signer and v2+v3 signing policy remain unchanged.
- ✅ v1.0 scope is **FROZEN**. No new feature work belongs in the finalization commit set.

## RELEASE RULE

v1.0 finalization may change only release metadata, build/sign/install helpers, and release documentation. Any behavior change discovered after this freeze belongs in a new candidate only if it blocks release acceptance; otherwise it moves to v1.1 / Signal V2 backlog.

No PR merge is implied by acceptance or finalization.

## ACCEPTED DEVICE EVIDENCE

- ✅ Android NotificationListener capture works and stored events are visible in Relay diagnostics.
- ✅ Relay → Cortex Local Bus V1 connectivity works.
- ✅ Relay marks delivery only after a correlated Cortex ACK for the exact `event_id`.
- ✅ Durable outbox survives Relay process death while Cortex is unavailable.
- ✅ The exact recovered pending event is delivered after Cortex returns, receives one correlated ACK, and leaves zero waiting backlog.
- ✅ Retry accounting is explainable and retries preserve the same V1 wire event identity.
- ✅ Notification lifecycle observes POSTED / UPDATED / REMOVED with stable notification-instance identity.
- ✅ Exact duplicate updates are suppressed.
- ✅ Android group-summary/container notifications are retained locally but do not create duplicate Cortex evidence.
- ✅ Useful WhatsApp child notifications are classified `HUMAN_MESSAGE` and receive Cortex ACKs.
- ✅ A second WhatsApp message arriving inside an already-live notification produces a distinct `signal-message-delta_...` logical signal and one Cortex ACK rather than replaying the old snapshot.
- ✅ Two WhatsApp account/profile surfaces are distinguishable from Android evidence using different source-profile and conversation identities where Android exposes that evidence.
- ✅ Rich MessagingStyle evidence, sender/conversation/timestamps, replyability, Person metadata and deterministic entity provenance are normalized.
- ✅ New-message delta entities/classification use only the new evidence, preventing stale older message content from contaminating the delta.
- ✅ Conservative signal/noise classification is in place without personal-priority reasoning inside Relay.

## IMPLEMENTATION / ACCEPTANCE BLOCKERS

- ✅ **1. Durable outbox across process death / reboot**
  - Disk-backed delivery copies are persisted before send.
  - Entries are removed only after correlated Cortex ACK or explicit terminal rejection.
  - Process-start / boot / package-replace recovery restores pending entries.
  - Real-device recovery + final delivery completion passed.

- ✅ **2. Notification lifecycle tracking**
  - Stable notification identity with POSTED / UPDATED / REMOVED state and update sequence.
  - Removal closes an instance; repost starts a new generation.

- ✅ **3. Meaningful delta / duplicate evidence**
  - Exact repeated snapshots are suppressed.
  - Android group summaries stay local.
  - Genuine MessagingStyle message updates produce new-message deltas and distinct logical delta identities.
  - Deterministic machine churn can be retained locally without consuming Cortex delivery.

- ✅ **4. Multi-account / conversation identity**
  - Source identity uses package + Android user/profile + UID evidence.
  - Conversation identity uses shortcut/conversation/Person evidence where available.
  - Real-device WhatsApp account/profile separation passed.

- ✅ **5. Rich evidence normalization + provenance**
  - MessagingStyle structures, sender, conversation, timestamps, replyability, Person and Android metadata are preserved.
  - URLs, phone numbers, OTPs, explicit dates/times, money, references/tracking/order-like identifiers and shown names carry source/span provenance.
  - Compact oversized payload handling preserves Relay normalization/provenance metadata.

- ✅ **6. Stable logical signal identity**
  - Ordinary lifecycle/content updates retain one logical notification signal identity.
  - Genuine new-message deltas get distinct logical delta identities.
  - Retry/replay remains anchored to the same captured event / V1 wire id.
  - No breaking `CORTEX_SIGNAL_V2` rollout was introduced.

- ✅ **7. Conservative signal/noise classification foundation**
  - human_message, email, call, sms, otp, banking, delivery, calendar, security, download, system_noise and other foundations exist.
  - `FORWARD / LOW_VALUE / DROP_CONFIRMED_NOISE` remains conservative.
  - Personal relevance/priority remains Cortex responsibility.

- ✅ **8. Combined real-device acceptance gate**
  - Cortex available → useful human-message child → one correlated ACK.
  - Cortex unavailable → durable pending event retained.
  - Relay killed/restarted while Cortex unavailable → exact pending event restored.
  - Cortex restored → same recovered event delivered → one ACK → zero waiting backlog.
  - Live WhatsApp notification updated with a second message → distinct message-delta signal → one ACK.
  - Group summaries remain local and do not create duplicate Cortex evidence.
  - Multi-account/profile identity remains distinguishable where Android evidence permits.
  - Final diagnostics show zero unexplained rejection/backlog.

## Candidate history

- Candidate 1 (`versionCode 10`) exposed Android child + group-summary duplicate delivery.
- Candidate 2 (`versionCode 11`) fixed duplicate summary delivery and proved account/profile separation, but exposed useful-child classification as `OTHER`.
- Candidate 3 (`versionCode 12`) fixed general Android conversation classification and passed notification, durable-outbox, multi-account, lifecycle and live MessagingStyle-delta acceptance.
- Final v1.0 (`versionCode 13`) contains no new behavioral feature beyond the accepted Candidate 3 scope; it is release finalization only.

## Explicitly deferred beyond v1.0

- Cortex → Relay capture-policy feedback loop.
- Breaking `CORTEX_SIGNAL_V2` rollout.
- Long-term personal memory or reasoning inside Relay — prohibited by product boundary.
- Cosmetic app-specific filtering that does not improve the general evidence gateway.

## Product invariant

**Relay captures, normalizes, preserves provenance, filters only confirmed noise, and delivers reliably. Cortex remains the brain: understanding, memory, reasoning, prioritization, suggestions and actions.**
