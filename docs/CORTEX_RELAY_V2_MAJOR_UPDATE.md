# Cortex Relay v2.0 — One Major Update Plan

## Release rule

Cortex Relay v2.0 is developed as **one major update only**.

- No feature-by-feature device APKs.
- Internal development may use many commits and CI checkpoints.
- The installed v1.0.1 build remains the stable device baseline during development.
- A v2 device APK is produced only after all implementation workstreams below are code-complete and CI-green.
- Device acceptance uses one combined v2 candidate. A new candidate is produced only if that combined candidate exposes a real blocker.
- Never uninstall the existing app. Final installation must be update-in-place with the existing permanent signer.
- Do not merge the v2 PR without explicit user approval.

## Product boundary

Relay is the Android evidence gateway. It may observe, normalize, preserve provenance, retain short-lived forensic evidence, expose available Android actions, execute a Cortex-authorized action, and report execution results.

Relay must **not** become a second personal brain. Personal relevance, memory, reasoning, prioritization, recommendations, and decisions remain Cortex responsibilities.

## v2 workstreams

### 1. Conversation continuity

- Preserve stable source/profile and conversation identities across lifecycle updates.
- Carry explicit Android shortcut / Person / participant / conversation evidence.
- Never claim two cross-app identities are the same person unless Cortex establishes that relationship from grounded evidence.
- Report insufficient identity evidence rather than inventing an account/person mapping.

### 2. Generic semantic evidence schemas

Normalize evidence into general signal families without app-specific personal heuristics:

- MESSAGE
- EMAIL
- CALL
- SMS
- OTP
- BANKING / PAYMENT
- DELIVERY
- CALENDAR
- SECURITY
- DOWNLOAD
- SYSTEM
- OTHER

Each semantic object must retain field-level provenance and raw source references.

### 3. Rich provenance and attachments

- Field provenance for title/body/expanded text/conversation/messages/People/actions/channel/shortcut metadata.
- URL, phone, OTP, date/time, money, reference/tracking/order-like identifiers and visible person names remain deterministic and grounded.
- Capture attachment/link/file/image metadata when Android exposes it.
- Preserve uncertainty explicitly.

### 4. Short-lived forensic buffer

- Local raw notification evidence buffer, target retention 24–72 hours.
- Operational/debug evidence only; not long-term personal memory.
- Bounded storage, deterministic expiry and diagnostics.
- Never block delivery because forensic retention fails.

### 5. Replay/debug engine

- Re-run saved forensic evidence through current normalization/classification without waiting for a new real notification.
- Replay must never silently redeliver to Cortex unless the user explicitly chooses a delivery test mode.
- Show before/after normalization and classification differences.

### 6. Observability / health

Expose enough operational health to answer quickly whether Relay is healthy:

- capture rate
- forwarded / filtered rate
- Cortex ACK latency
- retry rate
- waiting/outbox size
- oldest pending age
- last successful delivery
- lifecycle/delta counters
- forensic-buffer size/age
- replay results
- action execution results
- protocol/capability status

### 7. Cortex capture-policy feedback

Cortex may send bounded operational policy such as confirmed mechanical noise preferences or capability requests.

Policy feedback must not encode personal importance decisions into Relay. Relay must reject/ignore unsupported or over-broad policy and expose the decision in diagnostics.

### 8. Action capability extraction

For each signal, capture Android actions that are actually exposed, including where available:

- reply
- open
- dismiss
- mark read
- snooze
- other semantic Notification.Action operations

Each available action must have a stable action ID, provenance and execution constraints. Relay does not choose whether to execute it.

### 9. Action Bridge

Cortex may request execution of an exposed action by stable signal/action ID.

Required properties:

- explicit protocol request/result
- idempotency / duplicate protection where possible
- action still-valid checks
- clear failure reason when notification/action is no longer available
- execution audit record
- no action execution solely because Relay classified something as important

### 10. Cortex Signal V2 / Local Bus V2 evolution

Design a versioned protocol with backward-compatible negotiation during rollout:

- capability negotiation
- richer signal schema
- stable logical signal identity
- exact event identity
- correlated ACK/error
- action capability payloads
- action request/result
- policy feedback
- replay/debug markers
- schema/protocol versioning
- bounded payload handling and optional batching where safe

V1 remains the compatibility fallback until both sides have validated V2.

## Full System Test — mandatory v2 feature

The app must include an in-app **Full System Test** and generate a shareable debug report for review.

### Test result states

- `PASS` — deterministically verified in the current run.
- `FAIL` — executed and produced an incorrect result.
- `WARN` — works but current runtime/device state deserves attention.
- `NOT_IMPLEMENTED` — required v2 subsystem is not implemented yet; blocks v2 candidate creation.
- `NEEDS_REAL_EVENT` — cannot be safely proven with an in-process synthetic test; requires guided real-device evidence.

### Core self-tests

The runner must cover at least:

- app/package/version information
- required capture/access state
- local filesystem read/write
- durable outbox round-trip and duplicate behavior
- lifecycle POSTED/UPDATED/REMOVED/repost behavior
- meaningful MessagingStyle delta behavior
- stable conversation/logical identities
- deterministic entity/provenance extraction
- conservative noise filtering
- installed application-label resolution
- current Cortex connectivity and recent correlated ACK evidence
- current backlog/retry health
- forensic buffer
- replay engine
- generic semantic schemas
- action capability extraction
- action bridge
- policy feedback
- Signal V2 capability/protocol state
- observability metrics

### Real-event probes that must remain explicit

The in-app runner must not fake success for scenarios requiring Android/Cortex behavior outside the process. These remain `NEEDS_REAL_EVENT` until proven by guided acceptance, including where applicable:

- NotificationListener delivery from a real app
- process-death / reboot durable recovery
- real multi-account/profile evidence
- live notification update/new-message delta
- real Android action execution
- Cortex V2 negotiation/action round-trip

### Debug report

Generate a single shareable report with schema `CORTEX_RELAY_FULL_SYSTEM_TEST_V1` containing:

- run id and timestamps
- app/package/version/device build information
- overall status and counts by result state
- every test case id, area, result, summary and detail
- sanitized Relay runtime/connection/outbox health
- required real-event follow-ups
- no notification message bodies by default

The report is intended to be shared back to ChatGPT for review.

## v2 candidate gate

A v2 device candidate cannot be produced until:

1. CI is green on the exact candidate head.
2. Full System Test contains **zero `FAIL`**.
3. Full System Test contains **zero `NOT_IMPLEMENTED`**.
4. Every `WARN` is explained/accepted.
5. Every `NEEDS_REAL_EVENT` item has a named device-acceptance step.
6. One combined device candidate passes those real-event acceptance steps.

Only then can Cortex Relay v2.0 be called accepted.
