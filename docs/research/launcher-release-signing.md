# How open-source launchers sign releases

Research date: 2026-08-20

## Question

Quicklauncher plans to publish one standalone APK per stable GitHub release for Obtainium. It will not use Google Play or Play App Signing. How do established open-source launchers handle signing and publication, and which model fits Quicklauncher?

## Short answer

There is no common launcher practice to copy wholesale.

- Lawnchair has the clearest automated pipeline. GitHub Actions reconstructs the APK keystore from repository secrets, signs the APK in CI, publishes one APK, and creates a GitHub build-provenance attestation. The project also documents the expected APK certificate fingerprint and provides an official Obtainium configuration.
- Kvaesitso also signs nightly APKs in GitHub Actions. Its stable releases contain one maintainer-signed APK, but the stable signing step is not present in the public workflow. The project's own F-Droid repository republishes those stable APKs without replacing their APK signature.
- KISS does not expose a production signing key to GitHub Actions. Its release workflow builds and uploads a debug-signed APK and an unsigned release APK. F-Droid builds the installable package from source and signs that distribution with F-Droid's app key.
- Olauncher publishes one maintainer-signed APK in most GitHub releases, but its repository contains no release workflow or release signing configuration. The location and protection of its signing key are undocumented. F-Droid separately builds and signs its own package.

The examples split on whether CI should hold the production APK key. Lawnchair accepts that risk and compensates with a published certificate fingerprint and provenance. KISS avoids it but does not provide a production-signed GitHub APK. Kvaesitso and Olauncher publish useful GitHub APKs while leaving the most important key-handling detail private.

For Quicklauncher, the better trade is to keep the production key out of hosted CI. CI should build and test the release candidate. A maintainer should sign the exact candidate with an offline-protected key, verify it, and upload one standalone APK to a draft GitHub release. The project should publish the APK certificate fingerprint, the final APK's SHA-256 digest, and a release checklist. Two encrypted backups of the APK key and its signing lineage are mandatory. Key rotation can recover from a planned key change, but it cannot recover from losing every copy of the old key.

## Comparison

| Project | GitHub or project channel | Where APK signing happens | F-Droid relationship | APK assets | Checks and attestations | Rotation and Obtainium |
| --- | --- | --- | --- | --- | --- | --- |
| Lawnchair | Stable and nightly releases are built and published by GitHub Actions. | CI reconstructs a keystore from GitHub secrets and Gradle applies that signing configuration. | No F-Droid path was part of the reviewed release docs. | Current stable and nightly releases each expose one standalone APK. | Stable workflow creates a GitHub build-provenance attestation. Docs publish the GitHub certificate SHA-256 fingerprint. GitHub also exposes an asset digest. | README contains an official Obtainium link and a preconfigured nightly filter. No key-rotation procedure was found. |
| Kvaesitso | Stable releases expose one APK. Nightlies are Actions artifacts and enter the project's own F-Droid repository. | Nightly signing happens in CI with a secret keystore. The stable APK has a persistent custom certificate, but the stable signing operation is undocumented. | The project's repository copies the stable GitHub APK and nightly Actions artifact into an F-Droid repository. It signs repository metadata, not replacement APKs. | Current stable releases expose one standalone APK. | GitHub exposes an asset digest. No project checksum file or provenance attestation was found. | No official Obtainium configuration or key-rotation procedure was found. |
| KISS | Current workflow reacts to a GitHub release and uploads build outputs. Recent sampled releases currently contain no project-uploaded APK assets. | CI produces `app-debug.apk` and `app-release-unsigned.apk`; it does not create a production-signed GitHub APK. | F-Droid metadata builds from tagged source. It has no upstream `Binaries` or `AllowedAPKSigningKeys` entry, so this is the ordinary F-Droid-signed distribution rather than an upstream-signed reproducible-binary path. | The workflow is designed to upload two complete APKs, debug and unsigned release, not ABI splits. | No signed checksum or provenance for a production GitHub APK was found. F-Droid supplies its signed repository index and package hash. | GitHub is not currently a reliable install-and-update channel for Obtainium. No key-rotation procedure was found. |
| Olauncher | A maintainer uploads GitHub release APKs. The repository has no release workflow. | The current APK has a persistent maintainer certificate. The signing location, key storage, and publication steps are undocumented. | F-Droid metadata builds from tagged source and does not use upstream-signed binaries. | Most recent releases expose one standalone APK. Some older releases expose two behavior variants, not ABI splits. | GitHub exposes an asset digest. No project checksum file or provenance attestation was found. | Multiple APK variants would require an Obtainium APK filter. No official Obtainium configuration or key-rotation procedure was found. |

"Undocumented" is deliberate here. A missing public workflow does not prove that a maintainer signs on an offline machine. It only means the public repository does not establish where or how the key is used.

## Project evidence

### Lawnchair

Lawnchair's stable release workflow reads a base64 keystore and passwords from GitHub Actions secrets, builds the GitHub release variant, applies `actions/attest-build-provenance`, and passes one APK to a draft GitHub release. Its nightly workflow uses the same secret-to-keystore pattern and replaces a prerelease named `nightly` with one APK. Gradle falls back to the debug signing configuration if `keystore.properties` is absent, so the official workflow's secret injection is what distinguishes the signed official artifact from an ordinary checkout build. [Stable release workflow](https://github.com/LawnchairLauncher/lawnchair/blob/eed2baf4efe4cf49540cf4ec474942dc743b83cc/.github/workflows/release_update.yml), [nightly workflow](https://github.com/LawnchairLauncher/lawnchair/blob/eed2baf4efe4cf49540cf4ec474942dc743b83cc/.github/workflows/ci.yml), [Gradle signing configuration](https://github.com/LawnchairLauncher/lawnchair/blob/eed2baf4efe4cf49540cf4ec474942dc743b83cc/build.gradle)

The project documents SLSA provenance for stable releases starting with Lawnchair 15 Beta 1 and publishes the SHA-256 fingerprint of the GitHub APK certificate. That is unusually good user-facing verification for a launcher. The documentation also separates the GitHub and Play certificates. [Lawnchair verification guide](https://github.com/LawnchairLauncher/docs/blob/16ef85f95209a3746351e880715df36fa13d24b0/help/getting-started/install-and-setup/verify.md)

Lawnchair's README links directly to Obtainium. The encoded configuration tracks the dedicated nightly package, includes prereleases, filters for the "Lawnchair Nightly" release, and uses release date as the version. This is stronger than merely assuming Obtainium will guess the correct release. [Lawnchair download configuration](https://github.com/LawnchairLauncher/lawnchair/blob/eed2baf4efe4cf49540cf4ec474942dc743b83cc/README.md#download)

The current stable and nightly release records each have one project-uploaded APK. GitHub records a SHA-256 digest for each asset. That digest comes from GitHub's release service, while Lawnchair's attestation separately links the stable binary to the workflow and commit. [Lawnchair releases](https://github.com/LawnchairLauncher/lawnchair/releases), [GitHub's attestation model](https://docs.github.com/en/actions/concepts/security/artifact-attestations)

No Lawnchair key-rotation or lost-key procedure was found in the reviewed README, verification guide, Gradle configuration, or release workflows.

### Kvaesitso

Kvaesitso's nightly workflow reconstructs a keystore from GitHub secrets, sets a date-based version code, runs `assembleDefaultNightly`, and uploads one APK as an Actions artifact. Gradle assigns that `gh-actions` signing configuration only to the nightly build type. [Nightly workflow](https://github.com/MM2-0/Kvaesitso/blob/6b5d8e48acac79fa05ac839935ccee5b2cecc3ff/.github/workflows/build-nightly.yml), [Gradle build types](https://github.com/MM2-0/Kvaesitso/blob/6b5d8e48acac79fa05ac839935ccee5b2cecc3ff/app/app/build.gradle.kts)

Stable releases are different. The repository has no stable build or release workflow. The Gradle file assigns the normal debug signing configuration in `defaultConfig`, yet the released v1.40.2 APK carries a persistent custom certificate rather than an ordinary Android debug certificate. I downloaded the one APK asset whose GitHub SHA-256 digest is `5bbd146f15a14a8e29b2b4d960c17a09131fce951dba8f3c5909f6e2686dbe6f` and verified it with Android Build Tools 36 `apksigner`. Its certificate subject is `CN=U.N.Owen`, with certificate SHA-256 `bf65bbd617997397800d02e0ac2e45cee153151518545c23ef66b3cb2cfdf7bc`. This proves the stable artifact is signed with a project-controlled certificate. It does not reveal whether the maintainer uses a local Gradle override, signs after building, or uses an unpublished release system. [Kvaesitso v1.40.2 release](https://github.com/MM2-0/Kvaesitso/releases/tag/v1.40.2), [official `apksigner` verification reference](https://developer.android.com/tools/apksigner)

Kvaesitso's own F-Droid repository is a republishing layer. Its stable source downloads the first APK from each GitHub release. Its nightly source downloads the Actions artifact. The repository workflow injects a separate F-Droid repository keystore and runs `fdroid update`; it does not run `fdroid publish` to replace the APK signature. The repository index is signed, while the APK retains the upstream stable or nightly certificate. [Stable source adapter](https://github.com/MM2-0/fdroid/blob/d9fd22f10bbb450bcdc4642802dcadc942932b62/repo/src/sources/gh-release-source.ts), [nightly source adapter](https://github.com/MM2-0/fdroid/blob/d9fd22f10bbb450bcdc4642802dcadc942932b62/repo/src/sources/gh-action-source.ts), [repository workflow](https://github.com/MM2-0/fdroid/blob/d9fd22f10bbb450bcdc4642802dcadc942932b62/.github/workflows/build-repo.yml), [repository setup](https://github.com/MM2-0/fdroid/blob/d9fd22f10bbb450bcdc4642802dcadc942932b62/repo/scripts/setup.sh)

Kvaesitso documents direct installation from GitHub and prefers its F-Droid repository, but it does not publish an Obtainium configuration, stable certificate fingerprint, signed checksum, provenance attestation, or key-rotation procedure in the reviewed files. [Kvaesitso installation instructions](https://github.com/MM2-0/Kvaesitso/blob/6b5d8e48acac79fa05ac839935ccee5b2cecc3ff/readme.md#installation)

### KISS

KISS's release workflow runs lint and unit tests, builds debug and release variants, then uploads `app-debug.apk` and `app-release-unsigned.apk` to an existing GitHub release. It has no keystore or signing secret. The debug APK uses Gradle's debug certificate, and the filename explicitly identifies the release APK as unsigned. [KISS publish workflow](https://github.com/Neamar/KISS/blob/cad2c94f65b05e7aa3591d88f515a9381e17ff9f/.github/workflows/publish.yml), [KISS Gradle build types](https://github.com/Neamar/KISS/blob/cad2c94f65b05e7aa3591d88f515a9381e17ff9f/app/build.gradle)

The three most recent releases sampled through the GitHub Releases API had no project-uploaded assets as of the research date. Their release pages only exposed GitHub's generated source archives. The checked-in workflow would add the debug and unsigned APKs when it runs on a published or edited release, but neither file is a suitable production update for an established install base. [KISS releases](https://github.com/Neamar/KISS/releases)

The usable non-Play distribution is F-Droid. Its metadata checks out each tagged release and runs the Gradle build. The metadata contains neither `Binaries` nor `AllowedAPKSigningKeys`, the fields F-Droid uses to verify and publish an upstream developer-signed binary. This means F-Droid owns the signing identity for its KISS package. A GitHub APK signed later with a KISS maintainer key would not update an F-Droid-installed copy. [KISS F-Droid metadata](https://github.com/f-droid/fdroiddata/blob/f8e7943ac8a91ca7fc06562a9774312584f50101/metadata/fr.neamar.kiss.yml), [F-Droid build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/)

No production GitHub certificate, signed checksum, attestation, Obtainium configuration, or key-rotation procedure was found.

### Olauncher

Olauncher tells users to install through F-Droid, Play, or its latest APK. Its repository has no GitHub Actions workflow for building or publishing a release. The Gradle release build has no checked-in signing configuration. [Olauncher README](https://github.com/tanujnotes/Olauncher/blob/9c2fba1eea0435dc3b1959b8049acbd2508ef96b/README.md), [Olauncher Gradle configuration](https://github.com/tanujnotes/Olauncher/blob/9c2fba1eea0435dc3b1959b8049acbd2508ef96b/app/build.gradle)

The v6.7.19 GitHub release has one APK uploaded by the maintainer account. I downloaded the asset, matched GitHub's SHA-256 digest `e83773af04250c6f010cba74e363ceefb284cd68cdae75ff2ee040414fefb8ae`, and verified it with `apksigner`. Its certificate subject identifies the maintainer and its certificate SHA-256 is `a2bd5f2443fd4b43a039b73ebe254febbc7eb57a2993dff541de52c52e588149`. The APK is therefore suitable for updates from the same GitHub signing identity, but the repository does not document where the key lives or how the APK moves from build to release. [Olauncher v6.7.19 release](https://github.com/tanujnotes/Olauncher/releases/tag/v6.7.19)

Some Olauncher releases contain two APKs. The v6.3.15 notes describe them as different landscape-mode behaviors, not CPU-specific splits. Obtainium prompts the user when a release has several APKs unless a filter reduces them to one, and its background update path requires exactly one choice. Olauncher's newer one-APK releases avoid that ambiguity. [Olauncher v6.3.15 release](https://github.com/tanujnotes/Olauncher/releases/tag/v6.3.15), [Obtainium APK filtering](https://github.com/ImranR98/wiki.obtainium.imranr.dev/blob/74389e6fea3046c2bd8a2438964646c5a70b472e/pages/sources.en.md#app-sources)

F-Droid builds Olauncher from tagged source. Its metadata has no upstream `Binaries` or allowed signing certificate, so the F-Droid package has F-Droid's signing identity rather than the GitHub APK's maintainer identity. [Olauncher F-Droid metadata](https://github.com/f-droid/fdroiddata/blob/f8e7943ac8a91ca7fc06562a9774312584f50101/metadata/app.olauncher.yml)

No project-supplied checksum file, attestation, Obtainium configuration, or key-rotation procedure was found.

## What the evidence says about Quicklauncher

### Do not use F-Droid's key as the primary identity

Quicklauncher has chosen GitHub plus Obtainium, so its GitHub APK certificate is the product's durable update identity. Publishing a separately signed F-Droid build later would create a second, incompatible install lineage unless F-Droid verifies and republishes Quicklauncher's reproducible upstream APK. Obtainium's own documentation warns that an F-Droid package often has a different signer from its GitHub counterpart and cannot be updated across those sources. [Obtainium source notes](https://github.com/ImranR98/wiki.obtainium.imranr.dev/blob/74389e6fea3046c2bd8a2438964646c5a70b472e/pages/sources.en.md#other-sources), [F-Droid upstream-binary verification](https://f-droid.org/docs/Build_Metadata_Reference/#binaries)

### Keep exactly one installable APK in each stable release

"One APK" should mean one file ending in `.apk`, not one release asset total. A release may also contain a detached signature, checksum, SBOM, or notes. Obtainium can filter several APKs, but that configuration is extra failure-prone state. Its documentation says several candidate APKs force a manual choice and prevent silent background updates unless filters reduce the set to one. [Obtainium app tracking requirements](https://github.com/ImranR98/wiki.obtainium.imranr.dev/blob/74389e6fea3046c2bd8a2438964646c5a70b472e/pages/app_tracking.en.md#background-updates)

Quicklauncher targets phones on API 35 and later, so a single standalone APK is practical. It should not publish unsigned, debug, ABI-specific, or alternate-feature APKs beside the stable file. Put experiments in a different prerelease channel and use a different application ID if they need parallel installation.

### Prefer offline-protected signing over a hosted CI secret

Lawnchair demonstrates that CI signing can be transparent and verifiable. It also puts the only credential that can authorize updates into the hosted CI secret store. A compromised workflow, maintainer token, dependency action, or secret boundary could turn that key into a malicious update. Build attestations would identify the bad workflow but would not stop Android or Obtainium from accepting an APK signed by the compromised key.

KISS avoids this exposure but gives up a production-signed GitHub channel. Kvaesitso and Olauncher show that maintainers can publish a stable, persistent GitHub signing identity without checking the key into the repository, but neither project documents enough process to audit how safe that identity is.

Quicklauncher should make the missing process explicit:

1. CI checks out the release tag, runs all release gates, and builds an unsigned release candidate. The candidate and its build logs become immutable workflow artifacts.
2. A maintainer downloads that exact candidate on a dedicated signing machine. The production APK key is never copied into GitHub Actions, a developer workstation used for ordinary browsing, or the repository.
3. The maintainer signs with `apksigner`, then verifies the APK against the intended minimum SDK. The verification records package ID, `versionName`, increasing `versionCode`, signing certificate SHA-256, APK SHA-256, and supported signature schemes.
4. The maintainer uploads the final APK to a draft release containing exactly one `.apk`. A second check downloads the draft asset and repeats the verification before publication.
5. The release page and project docs publish the expected certificate fingerprint. The project also publishes the final APK digest. A checksum detects corruption, while the APK certificate establishes the Android update identity.

An Android APK signature already authenticates the APK to installed devices. A plain `.sha256` file hosted beside the APK is useful for transfer checks but does not create an independent trust source. If Quicklauncher wants provenance comparable to Lawnchair's, it must attest the final signed bytes, not only the unsigned CI candidate. GitHub's documentation is clear that an attestation links an artifact to a workflow and commit; it does not prove the software is safe. [GitHub artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)

### Back up the key before the first public release

Keep two encrypted backups in separate physical locations. Store the keystore password separately from at least one backup. Test restoration by signing and verifying a throwaway APK before shipping version 1.0. Record the certificate, alias, creation command, validity period, signing algorithms, and the exact `apksigner` invocation in a restricted recovery document.

Do not confuse a signed Git commit or verified GitHub release tag with the APK signature. The former authenticates source history. Android accepts an update based on the APK signing certificate and version code.

### Prepare rotation, but do not call it recovery from loss

Android's APK Signature Scheme v3 supports a proof-of-rotation lineage. `apksigner rotate` uses both the old and new signing keys to create that lineage. Quicklauncher's API 35 minimum means every supported device understands the modern rotation path. [APK Signature Scheme v3](https://source.android.com/docs/security/features/apksigning/v3), [`apksigner rotate`](https://developer.android.com/tools/apksigner#rotate-keys)

Generate and store a signing-lineage file when a planned rotation happens. Keep it with the same backup discipline as the keystore. Rotation still requires the old key to authorize the new one. If every copy of the current key is lost, there is no Play App Signing recovery service and no way to produce a trusted update for existing installs. The same is true if an attacker steals the key and creates its own authorized rotation first.

None of the four launcher projects reviewed here documents a lost-key or key-rotation runbook. Quicklauncher can do better with a short public policy and a restricted operational checklist.

## Recommended decision for the architecture plan

Use this as the answer to the release-signing decision:

> CI builds and tests the release candidate. A maintainer signs the exact candidate with an offline-protected, project-specific APK key, verifies the final bytes, and publishes a draft GitHub release with exactly one standalone APK. The project publishes the APK certificate fingerprint and final SHA-256 digest. It keeps two encrypted offline key backups and records a v3 signing-lineage procedure for planned rotation. The production key never enters hosted CI.

This costs one manual release step. For a launcher with no Play App Signing recovery, that is a sensible cost. The key is not merely a deployment credential. It is the permanent authority to update every installed copy.

## Scope and limits

The review used official repositories, current workflows and Gradle files, official release pages and release assets, F-Droid metadata, and Android, GitHub, F-Droid, and Obtainium documentation. It did not treat issue comments or third-party articles as evidence for key custody.

Public files can establish that a key is used in CI, as Lawnchair and Kvaesitso nightlies show. They cannot establish that an unpublished key is offline. Kvaesitso stable and Olauncher are therefore marked undocumented rather than offline-signed.

GitHub's automatically generated source archives are not counted as APK assets. GitHub's displayed asset digest is not counted as a project-supplied signed checksum. No claim about a key's hardware protection, backup practice, or maintainer access was made without first-party documentation.
