# Portable backup archive format v1

Quicklauncher portable backups use the media type `application/vnd.quicklauncher.backup` and the `.qlbackup` extension. Version 1 is an ordered binary envelope. It contains Quicklauncher configuration and imported assets only; it cannot contain package code, arbitrary device files, credentials, permission grants, or live widget IDs.

All integers are big-endian. Text is strict UTF-8. Section names match `[a-z0-9][a-z0-9._-]{0,127}`, are at most 128 encoded bytes, and are logical identifiers rather than paths.

## Envelope

| Field | Bytes | Version 1 value |
| --- | ---: | --- |
| Magic | 8 | ASCII `QLBACKUP` |
| Major | 2 | `1` |
| Minor | 2 | `0` |
| Feature flags | 4 | `0`; nonzero flags are unsupported until their behavior is published |
| Created-at epoch milliseconds | 8 | Non-negative signed integer supplied by the host clock |
| Backup kind | 1 | `1` manual, `2` automatic, `3` pre-restore |
| Reserved | 3 | Zero |
| Section count | 4 | `0..512`; a valid archive still requires one launcher-map section |

Each section then contains a one-byte type, three zero reserved bytes, a two-byte name length, a four-byte payload length, a 32-byte SHA-256 payload digest, the name, and the opaque payload. Section types are `1` launcher map, `2` theme profiles, `3` web adapters, and `4` imported asset. Exactly one launcher-map section is required. A `(type, name)` key cannot repeat.

Sections are sorted by numeric type and then ASCII name. Decoders reject non-canonical order, duplicate keys, malformed UTF-8, unknown types, checksum mismatch, truncation, and trailing bytes. This makes an encoding deterministic for the same immutable input and prevents ambiguous interpretations.

## Implementation limits

The first implementation accepts at most 512 sections, 16 MiB per payload, and 128 MiB for the complete archive. Lengths and the total envelope are checked before payload allocation. These are safety ceilings, not retention promises; automatic-backup retention is a separate policy.

Version 1 keeps feature flags at zero. A flag is not evidence that encryption exists: the archive codec returns `UNSUPPORTED_FEATURES` for every nonzero flag. Passphrase protection uses the separate wrapper specified below so the decoded inner archive remains canonical.

Automatic retention is outside the envelope. Quicklauncher schedules a one-shot persisted job for the next 03:00 local boundary, requires charging, and permits export only from 03:00 inclusive through 06:00 exclusive. Completion, failure, or system interruption schedules the next local night. A late job outside the window skips export. Enabling the feature becomes durable only after Android accepts the job. At startup, a stored enabled preference must have the expected pending job or successfully recreate it; otherwise Quicklauncher resets the preference to disabled. A failure to schedule the following night also disables the feature instead of displaying an enabled state with no pending work.

Inventory and retention do not trust document names. The reader applies its stored-byte limit and accepts plain archives only after the archive codec decodes them. It groups those archives by decoded kind and orders them by decoded creation time with document identity as a stable tie-breaker. The passphrase wrapper hides its inner header, so inventory recognizes its `QLCRYPT1` magic as a protected manual archive and may use the provider modification time only to order that manual display list. Preview still requires the passphrase and authenticates the complete wrapper before reading the archive. Automatic and pre-restore archives are never passphrase-protected. Automatic retention uses only decoded automatic kind and creation time, never provider time. It keeps the newest seven automatic archives and never prunes a renamed manual archive or a corrupt document whose name looks automatic. An automatic export reports failure if inventory or deletion prevents the retention policy from completing.

## Typed payload ownership

The launcher-map section owns the complete portable Room aggregate, including theme-profile records and destination-background references. It must not be copied into a theme section. Optional theme-profile, web-adapter, and asset-manifest sections use the versioned typed payload envelope and are omitted when their category has no entries. Imported asset bytes use individual asset sections named `font.<stable-key>`, `image.<stable-key>`, or `preview.<stable-key>`; a `(type, name)` key remains their canonical index.

Unknown launcher-map extensions and web-adapter entries are opaque name-and-byte records. Current readers preserve them byte for byte through decode, selected restore, app recreation, and later export. Quicklauncher stores restored web-adapter payload bytes and launcher extensions in private app storage, but does not register or execute an unknown adapter or extension. A selected web-adapter import with no adapter section clears the prior adapter payload; leaving that category unselected leaves it unchanged. Checked-in v1 launcher, web-adapter, and complete archive fixtures define this preservation boundary.

Every asset reference in a theme profile or destination background that the current schema can decode must have one matching section of the required kind. A wrong-kind section with the same stable key does not satisfy the reference. Readers reject malformed or unsupported asset names before restore review. Well-formed unreferenced sections remain valid because they may belong to opaque records from a future schema.

The asset manifest is optional because the section table is already a canonical SHA-256-authenticated index. If `ASSET/manifest` is present, it must contain at least one entry and exactly mirror every other asset section. Each entry name equals the asset section name and its content is the 32-byte SHA-256 digest of that section's payload. Empty, missing, extra, duplicate, short, and mismatched manifest entries fail preview. Readers accept an absent optional category and validate its typed envelope when present.

Themes and imported assets form one restore category. A restore selection cannot apply theme-profile or destination-background references without also importing the corresponding assets. Web adapters remain independent. Before the review screen appears, the reader validates every current decoded reference, canonical asset name, optional manifest entry, and optional typed payload. Well-formed extra assets remain valid for opaque future records.

## Restore transaction and recovery

Preview decodes and validates the complete archive without changing live launcher state. Restore re-reads the selected document, compares its digest with the preview, creates a pre-restore archive, stages selected auxiliary data, and then makes one revision-checked Room replacement. Cancellation, auxiliary failure, store failure, or a stale revision discards the auxiliary stage without masking the original result. Unknown contributions become quarantined records and every accepted launcher map retains the safe layout.

Theme asset staging uses a private `restore-<uuid>` directory. During normal reconciliation after process recreation, the asset store removes interrupted staging directories and installed assets that the live snapshot does not reference. Assets installed before an ordinary rejected Room commit are removed immediately by auxiliary rollback. This keeps a partial restore from becoming live launcher state.

The settings UI keeps manual archives and automatic snapshots on separate pages. Restore review exposes independent destination-map and web-adapter choices plus the coupled themes-and-assets choice. After a launcher-map restore, it exposes document reauthorization, app permission Settings, widget rebinding, quarantined-module review, and profile review actions. The recovery counts survive Activity recreation through saved instance state. A pending preview token does not survive recreation; the UI expires that review and requires a fresh preview.

## Passphrase protection wrapper

Passphrase protection wraps the encoded archive instead of setting an archive feature flag. The wrapper uses the eight-byte ASCII magic `QLCRYPT1`, version `1`, PBKDF2-HMAC-SHA256 with exactly 210,000 iterations and a 16-byte random salt, and AES-256-GCM with a 12-byte random nonce and 128-bit tag. The magic, version, derivation parameters, salt, and nonce are authenticated additional data. The wrapper then stores a four-byte ciphertext length followed by ciphertext and tag. Decoders bound the wrapper before allocation and reject wrong passphrases, parameter changes, truncation, trailing bytes, and authentication failure. Callers retain ownership of passphrase arrays and clear their working copies; the library never persists a passphrase.
