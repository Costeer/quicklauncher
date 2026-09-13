# Launcher release signing

Research date: 2026-08-20

## Outcome

This research recommended offline signing, but [ADR 0025](../adr/0025-sign-release-apks-in-github-actions.md) later chose protected GitHub Actions signing. The ADR is authoritative: separate stable and preview workflows reconstruct their keys after maintainer approval, sign one universal APK, and publish provenance, certificate fingerprints, and final digests. Pull-request jobs cannot access signing secrets.

Keep two encrypted backups of each key in separate locations and test restoration before 1.0. Record a v3 signing-lineage procedure for planned rotation. Rotation requires the old key and cannot recover from losing every copy.

The APK certificate is Quicklauncher's permanent Android update identity. A checksum detects transfer damage; the certificate authorizes updates. Attest the final signed APK.

## Release procedure

1. A protected tag workflow passes every release gate.
2. After maintainer approval, it reconstructs the channel key in temporary runner storage and signs with pinned tooling.
3. Verify application ID, `versionName`, increasing `versionCode`, minimum SDK, certificate fingerprint, APK digest, and signature schemes.
4. Upload one `.apk` to a draft release with provenance. Checksums, SBOM, and notes may be additional assets.
5. Download the draft asset, repeat verification, then publish.

Stable and preview channels use separate application IDs and keys. Do not publish debug, unsigned, ABI-specific, or alternate-feature APKs beside the channel's installable file. A future F-Droid package must preserve the upstream signature or remain a separate, incompatible install lineage.

## Evidence reviewed

| Project | Relevant finding |
| --- | --- |
| Lawnchair | CI reconstructs a signing key, publishes one APK, attests stable builds, documents its certificate, and provides Obtainium configuration. |
| Kvaesitso | CI signs nightlies. Stable releases have a persistent project certificate, but public files do not show the stable signing process. |
| KISS | GitHub automation produces debug-signed and unsigned APKs; F-Droid builds and signs the installable package. |
| Olauncher | Maintainers publish signed GitHub APKs, but public files do not document signing or key custody; F-Droid has a separate signer. |

"Undocumented" does not mean offline. Public files cannot establish unpublished key custody.

## Primary sources

- [Lawnchair stable workflow](https://github.com/LawnchairLauncher/lawnchair/blob/eed2baf4efe4cf49540cf4ec474942dc743b83cc/.github/workflows/release_update.yml) and [verification guide](https://github.com/LawnchairLauncher/docs/blob/16ef85f95209a3746351e880715df36fa13d24b0/help/getting-started/install-and-setup/verify.md)
- [Kvaesitso nightly workflow](https://github.com/MM2-0/Kvaesitso/blob/6b5d8e48acac79fa05ac839935ccee5b2cecc3ff/.github/workflows/build-nightly.yml) and [stable release](https://github.com/MM2-0/Kvaesitso/releases/tag/v1.40.2)
- [KISS publish workflow](https://github.com/Neamar/KISS/blob/cad2c94f65b05e7aa3591d88f515a9381e17ff9f/.github/workflows/publish.yml) and [F-Droid metadata](https://github.com/f-droid/fdroiddata/blob/f8e7943ac8a91ca7fc06562a9774312584f50101/metadata/fr.neamar.kiss.yml)
- [Olauncher release](https://github.com/tanujnotes/Olauncher/releases/tag/v6.7.19) and [F-Droid metadata](https://github.com/f-droid/fdroiddata/blob/f8e7943ac8a91ca7fc06562a9774312584f50101/metadata/app.olauncher.yml)
- [GitHub artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)
- [APK Signature Scheme v3](https://source.android.com/docs/security/features/apksigning/v3) and [`apksigner rotate`](https://developer.android.com/tools/apksigner#rotate-keys)
- [Obtainium source behavior](https://github.com/ImranR98/wiki.obtainium.imranr.dev/blob/74389e6fea3046c2bd8a2438964646c5a70b472e/pages/app_tracking.en.md#background-updates)
