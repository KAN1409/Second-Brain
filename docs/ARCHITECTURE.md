# Architecture

Dependency direction:

```text
Android framework adapters
        ↓
     domain
        ↓
repository interfaces
        ↑
data implementations
        ↓
Room / AppSearch / local AI / cloud AI
```

Rules enforced by module layout:

- Feature modules do not depend on Room DAOs.
- `domain` does not depend on Android framework APIs.
- Android capture services depend on domain contracts, not database implementations.
- AI engines implement interfaces from `ai:api`.
- Room is canonical; AppSearch is disposable.
