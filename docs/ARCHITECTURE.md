<!--
SPDX-License-Identifier: GPL-3.0-only
SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Module-dependency architecture

pcontacts is a multi-module Android project. `:core:sync` is the integration
layer that bridges Proton API access and cryptography with the Android
contacts stack. Feature modules reach those subsystems only through
`:core:sync`, never directly (ADR-0011). No module depends on `:app`.

```mermaid
graph TD
    subgraph app["  :app  "]
        APP[":app"]
    end

    subgraph features["  :feature:*  "]
        ONBOARDING[":feature:onboarding"]
        SETTINGS[":feature:settings"]
    end

    subgraph core["  :core:*  "]
        SYNC[":core:sync"]
        CRYPTO[":core:crypto"]
        PROTON_API[":core:proton-api"]
        PROTON_CONTACTS[":core:proton-contacts"]
        CONTACTS_WRITER[":core:contacts-writer"]
        STORAGE[":core:storage"]
        LOGGING[":core:logging"]
    end

    subgraph tools["  :tools:*  "]
        LINT[":tools:lint"]
    end

    %% :app dependencies
    APP --> SYNC
    APP --> STORAGE
    APP --> LOGGING
    APP --> ONBOARDING
    APP --> SETTINGS

    %% :feature:onboarding dependencies
    ONBOARDING --> SYNC
    ONBOARDING --> LOGGING

    %% :core:sync dependencies (the integration hub)
    SYNC --> PROTON_API
    SYNC --> CRYPTO
    SYNC --> STORAGE
    SYNC --> LOGGING
    SYNC --> CONTACTS_WRITER
    SYNC --> PROTON_CONTACTS

    %% :core:proton-contacts dependencies
    PROTON_CONTACTS --> PROTON_API
    PROTON_CONTACTS --> LOGGING

    %% :core:proton-api dependencies
    PROTON_API --> LOGGING

    %% :core:contacts-writer dependencies
    CONTACTS_WRITER --> LOGGING

    %% :core:storage dependencies
    STORAGE --> LOGGING

    %% lintChecks (dashed = build-time only)
    APP -.->|lintChecks| LINT
    ONBOARDING -.->|lintChecks| LINT
    SETTINGS -.->|lintChecks| LINT
    SYNC -.->|lintChecks| LINT
    CONTACTS_WRITER -.->|lintChecks| LINT
    STORAGE -.->|lintChecks| LINT

    %% Styling
    classDef boundary fill:none,stroke:#e74c3c,stroke-width:2px,stroke-dasharray:5 5
    classDef featureMod fill:#dbeafe,stroke:#2563eb
    classDef coreMod fill:#d1fae5,stroke:#059669
    classDef appMod fill:#fef3c7,stroke:#d97706
    classDef toolMod fill:#f3e8ff,stroke:#7c3aed

    class APP appMod
    class ONBOARDING,SETTINGS featureMod
    class SYNC,CRYPTO,PROTON_API,PROTON_CONTACTS,CONTACTS_WRITER,STORAGE,LOGGING coreMod
    class LINT toolMod
```

**Legend**

- Solid arrows = `implementation` or `api` project dependencies (runtime).
- Dashed arrows = `lintChecks` (build-time only).
- **ADR-0011 boundary:** `:feature:*` modules must not depend on `:core:crypto`
  or `:core:proton-api` directly. They reach those layers exclusively through
  `:core:sync`.
- **ADR-0015 boundary:** no module may introduce Google Play Services,
  Firebase, analytics, or telemetry dependencies. The `:app:checkLicense` task
  enforces this at build time.
- `:core:crypto`, `:core:proton-api`, `:core:proton-contacts`, and
  `:core:logging` are **pure-JVM** modules (testable without an emulator).
- `:tools:lint` is consumed via `lintChecks` by every Android module; it
  enforces the `pcontacts.SensitiveLog` rule (ADR-0015).
