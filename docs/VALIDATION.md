# Validation

## Automated

CI performs a clean Android build and unit tests on JDK 17 / API 37.

Current deterministic unit coverage includes:

- Unicode/whitespace normalization.
- SHA-256 repeatability.
- SimHash distance behavior.
- Screen near-duplicate keep/discard rules.
- Sensitive-package default policy behavior.

## M1 physical-device checks

See `docs/M1-RELIABLE-CAPTURE.md` for the full capture acceptance sequence.

Release-blocking privacy invariants for this checkpoint:

- capture while master-paused = 0
- password-field persistence = 0
- explicitly blocked per-app source persistence = 0
- cloud AI default per app = OFF
- sensitive-app notification default = OFF

The container used to prepare the source package does not provide a complete Android API 37 SDK/dependency cache, so the authoritative Android compile check is the included GitHub Actions workflow or a local Android SDK 37 build.
