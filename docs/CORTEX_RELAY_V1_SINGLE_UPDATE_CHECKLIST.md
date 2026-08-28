# Cortex Relay v1.0 — Single Update Acceptance Checklist

This file is the source of truth for the next device release.

## Release rule

Do **not** publish feature-by-feature device APKs for intermediate progress. Development may happen in several commits. Once implementation blockers **1–7** are code-complete and CI-green, produce **one combined v1.0 device candidate** containing all of them. Use that same candidate for blocker **8**, the combined real-device acceptance test. If blocker 8 passes, that candidate is the accepted v1.0 build; do not create another cosmetic rebuild merely to call it final.

If the combined device test exposes a real blocker, keep it red, fix the blocker, and repeat only because the candidate failed acceptance — never because an individual sub-feature deserves its own APK.

Status legend:
- ✅ GREEN — implemented and validated enough for its current gate
- ❌ RED — incomplete or not yet validated; blocks v1.0 acceptance

## Already green

- ✅ **Android capture works** — NotificationListener capture is running and stored events are visible in Relay diagnostics.
- ✅ **Cortex Local Bus V1 connectivity works** — `CORTEX_INGEST_V1`, connector `second_brain`, endpoint connected.
- ✅ **Per-event Cortex ACK correlation works** — Relay reports delivery only after Cortex ACKs the exact `event_id`; `Messenger.send()` alone is not treated as delivered.
- ✅ **Retry-safe V1 event identity works within the current process** — retries reuse the same wire event id and Cortex can return duplicate acceptance.
- ✅ **Recent Signals is human-readable and inspectable** — app/source, sender/title, preview, delivery state and tap-for-details exist.
- ✅ **Diagnostic accounting is explicit** — captured events, send attempts, ACKed deliveries, retry/failure incidents, and per-signal delivery accounting are exported.
- ✅ **Update-in-place / permanent signer path works** — package remains `com.kareem.secondbrain`; permanent signer is preserved; installation uses `pm install -r`.

## IMPLEMENTATION BLOCKERS — 1–7 must be green before the combined device candidate

- ❌ **1. Durable outbox across process death / reboot**
  - Persist undelivered delivery copies locally.
  - Re-open pending outbox entries after Relay process restart and device reboot.
  - Remove an entry only after correlated Cortex ACK or explicit terminal rejection.
  - Preserve the same logical/wire event id across retries.

- ❌ **2. Notification lifecycle tracking**
  - Track `POSTED → UPDATED → REMOVED` for the same Android notification identity.
  - Do not treat every notification update as an unrelated event.
  - Keep genuine new messages inside an updated MessagingStyle notification distinguishable as new evidence.

- ❌ **3. Meaningful delta detection**
  - Compare updates for the same notification identity.
  - Forward meaningful changes only.
  - Suppress machine churn such as percentage/progress-only changes when deterministic.
  - Never suppress uncertain evidence merely because it looks low-value.

- ❌ **4. Multi-account / conversation identity**
  - Resolve Android user/profile, notification key, shortcut/conversation metadata, Person metadata and available account hints into a stable source/conversation identity when Android exposes enough evidence.
  - Do not assume package name alone identifies an account.
  - Unit-test profile/account-scope separation before the candidate; validate the user's two-WhatsApp-account scenario in blocker 8.

- ❌ **5. Rich evidence normalization + provenance**
  - Preserve full MessagingStyle message structure.
  - Normalize sender, conversation, timestamps, replyability and Android metadata.
  - Deterministically extract visible URLs, phone numbers, OTPs, explicit dates/times, money values, order/reference/tracking numbers and shown names.
  - Every extracted entity must retain source span/provenance; Relay must not invent entities.

- ❌ **6. Stable logical signal identity**
  - Define one logical signal identity that survives retries and lifecycle updates without confusing a true new message with an update.
  - Keep current V1 wire compatibility; do not introduce breaking `CORTEX_SIGNAL_V2` unilaterally.

- ❌ **7. Conservative signal/noise classification foundation**
  - Classify signal type, not personal importance: human_message, email, call, sms, otp, banking, delivery, calendar, security, download, system_noise, other.
  - Use `FORWARD / LOW_VALUE / DROP_CONFIRMED_NOISE` conservatively.
  - Personal priority/relevance remains Cortex's job.
  - App-specific nuisance rules are not milestones by themselves.

## DEVICE ACCEPTANCE GATE — run once on the combined candidate

- ❌ **8. One combined real-device acceptance test**
  - Cortex available: real WhatsApp message → captured → delivered → exact ACK / Cortex signal id.
  - Cortex unavailable: real message → captured → persists in durable outbox.
  - Kill/restart Relay (and reboot if practical) while Cortex unavailable → pending signal remains.
  - Restore Cortex → same pending signal delivers once logically and receives correlated ACK.
  - Updated notification does not create unrelated duplicate evidence.
  - New MessagingStyle message inside an updated notification is preserved as new evidence.
  - Two WhatsApp accounts remain distinguishable where Android evidence permits; if Android exposes no account discriminator, Relay must report that evidence is insufficient rather than invent an account identity.
  - Diagnostic report shows zero unexplained backlog/rejections and explains any retries per signal.

## Explicitly not required before v1.0

These are valuable later but must not distract from the blockers above:

- Cortex → Relay capture-policy feedback loop.
- Full breaking `CORTEX_SIGNAL_V2` rollout.
- Long-term personal memory or reasoning inside Relay (prohibited by product boundary).
- Cosmetic app-specific filtering work that does not improve the general evidence gateway.

## Product invariant

**Relay captures, normalizes, preserves provenance, filters only confirmed noise, and delivers reliably. Cortex remains the brain: understanding, memory, reasoning, prioritization, suggestions and actions.**
