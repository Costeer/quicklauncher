# Phase 9 release-hardening scope

Phase 9 turns the completed product into a reproducible release candidate. It does not publish a release or use production signing material. The acceptance IDs and governing documents are indexed in `docs/agents/validation-index.md`; execution is split in `phase-9-packets.md`.

## Accessibility contract

`P9-A11Y` requires one evidence matrix covering every production host and contribution surface. The authoritative row-by-row inventory, evidence anchors, device requirements, and true gaps are recorded in [`phase-9-accessibility-evidence.md`](phase-9-accessibility-evidence.md). Checked-in tests must prove 2x text without clipped or hidden controls, complete and non-sensitive TalkBack semantics, deterministic focus traversal, keyboard and D-pad activation, zero-duration/reduced-motion behavior, predictive-Back commit and cancellation, and a non-drag command for every edit. Declarative metadata alone is not evidence. Visual checks, rendered semantics tests, and device behavior may each satisfy only the rows they directly exercise.

## Performance contract

`P9-PERF` requires an isolated macrobenchmark module that targets the stable non-debug app without depending on host or contribution implementations. It measures the five ADR 0019 scenarios on the pinned API 35 AOSP release environment and records raw benchmark output, build identity, iteration count, and device state. The separately authorized API 37 GrapheneOS compatibility run does not replace or supply those measurements.

The first release thresholds are acceptance limits, not claims of results:

| Scenario | Fixture and measure | Limit |
| --- | --- | --- |
| Home entry | Ten cold starts; `timeToInitialDisplayMs` | p95 <= 1,500 ms |
| Spatial gesture | Twenty cardinal transitions after warmup; CPU frame duration and frame overrun | p95 <= 32 ms CPU frame duration and p95 <= 16 ms frame overrun |
| App-catalog loading | Fixed bounded synthetic catalog from the benchmark-side request, including process/receiver startup, until the receiver confirms the catalog snapshot was published | p95 <= 750 ms |
| Search | Fixed local query from committed input until the result-ready trace | p95 <= 300 ms |
| Current plus neighbors | Fixed five-destination fixture after cardinal traversal; production-app peak resident memory. `SpatialNavigatorTest.frame contains only the current destination and its cardinal neighbors` separately proves the structural composition bound. | p95 <= 256 MiB and structurally no more than five composed destination surfaces |

A result is `not run`, not a pass, until the raw output exists for the named environment. The gate fails on a missing scenario, missing metric, threshold breach, crash, or skipped iteration.

## Security and privacy contract

`P9-SECURITY` inventories storage, network behavior, exported components, permissions, Android backup inclusion and exclusion, logs, diagnostics, secrets, dependencies, and release configuration. Every sensitive path must have a positive ownership rule and a negative leakage check. Findings are fixed or recorded as release blockers; absence inferred from documentation is insufficient.

## Release artifact contract

`P9-RELEASE` keeps stable and preview application IDs, signing identities, release environments, and assets separate. Pull requests have no signing-secret path. A tag-aligned protected workflow produces exactly one universal APK, attests the final signed bytes, and emits its SHA-256 and certificate fingerprint. A verifier downloads the draft asset and independently checks the tag/version, monotonically increasing version code, channel application ID, minimum SDK, signature schemes, certificate expectation, digest, and provenance subject digest. Local verification uses generated test-only keys and fixtures; production keys are never read or reconstructed.

## Release channels

`P9-CHANNELS` provides checked stable and preview Obtainium configurations and release metadata. Each channel selects only its one universal APK and rejects the other channel's application ID or certificate. There is no in-app updater.

## Key recovery drill

`P9-KEYS` uses generated disposable keystores only. The drill creates two encrypted backups in separate temporary locations, destroys the working copy, restores each backup independently, proves the same public certificate, exercises the documented v3-lineage command path, and removes temporary plaintext material. It records commands and public fingerprints, never passwords or private bytes.

## User documentation

`P9-DOCS` covers verified installation, upgrades and channel separation, backup and restore, recovery, permissions, privacy, and known limitations. Commands use published digests and certificate fingerprints as inputs rather than embedding an unverified release identity.

## Exit condition

`P9-GATES` closes only when every first-release contract maps to final-fingerprint evidence; applicable local, visual, accessibility, benchmark, lint, dependency, license, workflow, privacy, security, release, and repository gates pass; required authorized device evidence is recorded; both code-review axes pass separately; and a downloaded draft APK is shown to match its tag, channel application ID, expected certificate, attested provenance subject, and digest. External evidence stays explicitly open when authority has not been granted or when a required protected-release prerequisite is unavailable.
