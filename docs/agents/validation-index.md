# Validation index

This index maps acceptance IDs to the smallest governing document set. Open the named headings first. Use source and test search to narrow implementation reads.

## Shared rules

| ID | Concern | Governing sections |
| --- | --- | --- |
| SH-BOUNDARY | Module and host ownership | `CONTEXT.md`; ADR 0001, 0003, 0005, 0010, and 0027; `docs/contracts/shared-contract-rules.md` "Ownership boundary" and "Lifecycle and data" |
| SH-STORE | Transactional persistence | ADR 0012 and 0030; current phase evidence "Persistence and migration" |
| SH-QUALITY | Accessibility and quality | ADR 0019 and 0029; `docs/contracts/shared-contract-rules.md` "UI, accessibility, and performance" |
| SH-RECOVERY | Safe fallback and recovery | ADR 0018 and 0031; `docs/status/current-state.md` relevant phase section |
| SH-PRIVACY | Data minimization | ADR 0007, 0009, and 0017; current phase evidence privacy section |

## Phase 5

| ID | Concern | Governing sections |
| --- | --- | --- |
| P5-WIDGET | Widget lifecycle and placement | ADR 0003 and 0006; `docs/status/phase-5-device-evidence.md` "Required API 35 AOSP run" |
| P5-PROFILE | Work profile and Private Space | ADR 0007; `docs/status/phase-5-device-evidence.md` "Required GrapheneOS run" |
| P5-NOTIFY | Notification recovery | ADR 0021 and 0028; `docs/status/phase-5-device-evidence.md` "Required GrapheneOS run" |
| P5-FOLDER | Folder and item actions | ADR 0016, 0021, and 0022; Phase 5 status section in `docs/status/current-state.md` |
| P5-STORE | Phase 5 persistence | SH-STORE; Phase 5 status and device evidence |
| P5-WIRING | Production wiring and boundaries | SH-BOUNDARY, SH-QUALITY, and SH-RECOVERY |

## Phase 6

| ID | Concern | Governing sections |
| --- | --- | --- |
| P6-SESSION | Search session ownership | ADR 0005 and 0008; `docs/contracts/search-provider.md` "Interface" and "Ownership and validation"; `docs/status/phase-6-search-evidence.md` "Implemented architecture" |
| P6-PRIVACY | Result validation and profiles | ADR 0007 and 0008; SH-PRIVACY; Phase 6 evidence "Privacy and boundary audit" |
| P6-RANK | Ranking and history | ADR 0008 and 0030; Phase 6 evidence "Automated coverage" |
| P6-EXEC | Typed execution | ADR 0021 and 0022; `docs/contracts/launcher-command.md`; Phase 6 evidence "Implemented architecture" |
| P6-PROVIDERS | Production providers | ADR 0008, 0020, and 0027; search and command contracts; `docs/research/phase-6-search-platform-behavior.md` relevant provider heading |
| P6-UI | Presentation and lifecycle | ADR 0010, 0019, and 0028; search contract; Phase 6 evidence "Automated coverage" |
| P6-BOUNDARY | Modules, privacy, and bounds | SH-BOUNDARY and SH-PRIVACY; Phase 6 evidence "Privacy and boundary audit" |

## Phase 7

| ID | Concern | Governing sections |
| --- | --- | --- |
| P7-RESOLVE | One resolved host theme | ADR 0012 and 0014; `docs/status/phase-7-theme-evidence.md` "One resolved host theme" |
| P7-PALETTE | Profiles and palettes | ADR 0012 and 0014; Phase 7 evidence "Profiles and palettes" |
| P7-PERSIST | Theme persistence | SH-STORE; Phase 7 evidence "Persistence and migration" |
| P7-ASSET | Font and image imports | ADR 0003, 0012, and 0014; Phase 7 evidence "Fonts" and "Backgrounds and image-derived themes" |
| P7-ICON | Nova and ADW subset | ADR 0014; Phase 7 evidence "Nova and ADW icon packs" |
| P7-WALLPAPER | Wallpaper commands | ADR 0021 and 0022; Phase 7 evidence "Wallpaper and Settings" |
| P7-RECOVERY | Settings and recovery | SH-QUALITY and SH-RECOVERY; Phase 7 evidence "Findings resolved during validation" |

## Phase 8

| ID | Concern | Governing sections |
| --- | --- | --- |
| P8-ENVELOPE | Deterministic archive envelope | ADR 0017 and 0023; `docs/architecture/portable-backup-archive-v1.md`; `docs/status/phase-8-backup-scope.md` "Slice 1" |
| P8-SCHEMA | Typed portable payloads | ADR 0002, 0012, 0013, 0017, and 0030; archive format and Phase 8 scope |
| P8-SAF | Folder authorization and streaming | ADR 0003, 0017, and 0028; Phase 8 scope remaining slice 2 |
| P8-RETENTION | Automatic snapshot scheduling and retention | ADR 0017; Phase 8 scope remaining slice 3 |
| P8-CRYPTO | Passphrase authenticated encryption | ADR 0017; archive format feature flags; Phase 8 scope remaining slice 4 |
| P8-RESTORE | Staged atomic restore | ADR 0017, 0030, and 0031; SH-STORE and SH-RECOVERY; Phase 8 scope remaining slice 5 |
| P8-REAUTH | Post-restore reauthorization | ADR 0006, 0007, 0013, 0017, and 0028; Phase 8 scope remaining slice 6 |
| P8-SUPPORT | Redacted support bundles | ADR 0007 and 0017; SH-PRIVACY; Phase 8 scope remaining slice 7 |
| P8-GATES | Cross-version and device evidence | ADR 0019 and 0029; SH-QUALITY; Phase 8 scope exit condition |

## Phase 9

| ID | Concern | Governing sections |
| --- | --- | --- |
| P9-A11Y | Release accessibility matrix | ADR 0019, 0022, 0028, and 0029; SH-QUALITY; `docs/status/phase-9-release-scope.md` "Accessibility contract"; authoritative row-by-row matrix `docs/status/phase-9-accessibility-evidence.md` |
| P9-PERF | Measured release performance | ADR 0019, 0024, and 0029; SH-QUALITY; Phase 9 scope "Performance contract" |
| P9-SECURITY | Security and privacy audit | ADR 0007, 0009, 0014, and 0017; SH-BOUNDARY and SH-PRIVACY; Phase 9 scope "Security and privacy contract" |
| P9-RELEASE | Identity, signing, provenance, and downloaded-artifact verification | ADR 0009, 0024, and 0025; `docs/research/launcher-release-signing.md` "Release procedure"; Phase 9 scope "Release artifact contract" |
| P9-CHANNELS | Stable, preview, and Obtainium metadata | ADR 0009 and 0024; Phase 9 scope "Release channels" |
| P9-KEYS | Test-only key recovery and lineage drill | ADR 0025; release-signing research "Outcome"; Phase 9 scope "Key recovery drill" |
| P9-DOCS | Installation, upgrade, backup, recovery, permission, privacy, and limitation guidance | ADR 0009, 0017, 0023, 0024, and 0028; Phase 9 scope "User documentation" |
| P9-GATES | Complete contract and release evidence | ADR 0019, 0025, 0026, and 0029; SH-QUALITY, SH-RECOVERY, and SH-PRIVACY; Phase 9 scope exit condition |

## Reading rule

The acceptance ID chooses the initial context. Search another document when the indexed text names an unresolved dependency or the code contradicts it. Record that extra source in the packet so later work can reproduce the decision.
