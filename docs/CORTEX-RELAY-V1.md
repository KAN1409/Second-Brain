# Cortex Relay V1

Cortex Relay is the Android evidence gateway for Cortex.

## Product boundary

Relay is responsible for:

- observing Android evidence,
- normalizing source metadata,
- preserving provenance,
- conservative signal/noise classification,
- reliable transport toward Cortex.

Cortex remains responsible for understanding, personal memory, reasoning, prioritization, recommendations, actions, and learning.

Relay must not infer why evidence matters to the user or decide what deserves attention.

## V1 compatibility

The validated Local Bus V1 contract remains frozen:

- package: `com.kareem.cortex`
- service: `com.kareem.cortex.CortexLocalBusService`
- action: `com.kareem.cortex.LOCAL_BUS_V1`
- protocol: `CORTEX_INGEST_V1`
- connector id: `second_brain`
- capability: `NOTIFICATIONS`
- transport: Android `Messenger`

V1 removes a queued delivery copy after `Messenger.send()` accepts it. The current ACK has no required per-event correlation field, so Relay must not represent V1 ACKs as proof that one exact signal was durably accepted.

## First Relay refactor slice

This slice intentionally avoids a breaking protocol revision. It adds:

1. A Relay operational dashboard instead of Timeline/Search/Ask as the main surface.
2. Process-session diagnostics for Captured, Forwarded, Filtered, Waiting, and Failed/Retry events.
3. Conservative notification filtering with `FORWARD`, `LOW_VALUE`, and `DROP_CONFIRMED_NOISE` decisions.
4. Richer Android notification evidence: ranking importance, Android user/profile id, groups, shortcut/channel metadata, actions/replyability, Person metadata, and richer MessagingStyle metadata.
5. Cortex Relay display branding while preserving `applicationId = com.kareem.secondbrain` for update-in-place compatibility.

## Conservative filtering invariant

Uncertainty must preserve evidence.

The first confirmed-noise rule suppresses forwarding only for ongoing `com.android.systemui` charging notifications that contain an explicit battery percentage and charging marker. Other persistent SystemUI state is classified `LOW_VALUE` but is still forwarded.

Local capture happens before the forwarding filter, so V1 forwarding suppression does not roll back the locally stored capture.

## Deliberately deferred

The following require joint Cortex + Relay protocol design and are not introduced by this V1 refactor:

- `CORTEX_SIGNAL_V2`,
- correlated per-signal ACK,
- durable outbox semantics,
- lifecycle POSTED/UPDATED/REMOVED identity,
- cross-update meaningful-delta identity,
- Cortex-to-Relay capture-policy feedback.

Those changes must preserve stable signal identity and idempotency without breaking the validated V1 path.
