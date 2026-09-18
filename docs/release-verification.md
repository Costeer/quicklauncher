# Verify a Quicklauncher release

Verification answers five separate questions: does the tag match the app version, is this the expected channel application ID, did the permanent channel certificate sign it, does GitHub provenance name the same bytes and source commit, and does the downloaded digest match?

## Required inputs

From one GitHub release, obtain:

- the single channel `-universal.apk`;
- `release-metadata.json`;
- the `.sha256` asset;
- the tag and source commit;
- the published stable or preview certificate SHA-256 fingerprint.

Never infer the expected certificate from the downloaded APK itself. Compare it with a fingerprint already trusted through the project's release documentation or another independently authenticated maintainer channel.

Install GitHub CLI, Git, Python 3, and Android SDK Build Tools 35.0.0. Set concrete paths and the independently trusted certificate first:

```sh
export CHANNEL=stable
export TAG=v1.0.0
export APK=quicklauncher-stable-v1.0.0-universal.apk
export CERTIFICATE_SHA256=replace_with_independently_trusted_fingerprint
export ANDROID_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
export GH_TOKEN="$(gh auth token)"
```

Resolve the tag from a fresh GitHub fetch rather than from release metadata, and resolve the prior maximum channel `versionCode` independently from earlier GitHub release metadata:

```sh
verify_repo="$(mktemp -d)"
git -C "$verify_repo" init -q
git -C "$verify_repo" fetch -q --depth=1 \
  https://github.com/Costeer/quicklauncher.git "refs/tags/$TAG:refs/tags/$TAG"
export SOURCE_COMMIT="$(git -C "$verify_repo" rev-list -n 1 "$TAG")"
export PREVIOUS_CODE="$(python3 tools/release/previous_version_code.py \
  --channel "$CHANNEL" --repository Costeer/quicklauncher --current-tag "$TAG" \
  --exclude-existing-current)"
```

`--exclude-existing-current` is verification-only: it requires exactly one existing release for `TAG`, excludes that release, and returns the greatest `versionCode` from the other releases in the same channel. Release workflows omit the flag so an existing current-tag release remains a hard failure.

Verify the downloaded checksum file and GitHub's attestation:

```sh
sha256sum --check "$APK.sha256"
gh attestation verify "$APK" \
  --repo Costeer/quicklauncher --format json > provenance.json
```

Then run the repository verifier:

```sh
python3 tools/release/verify_release.py \
  --apk "$APK" \
  --checksum "$APK.sha256" \
  --metadata release-metadata.json \
  --provenance-statement provenance.json \
  --channel "$CHANNEL" \
  --tag "$TAG" \
  --previous-version-code "$PREVIOUS_CODE" \
  --expected-source-commit "$SOURCE_COMMIT" \
  --expected-certificate-sha256 "$CERTIFICATE_SHA256" \
  --aapt "$ANDROID_BUILD_TOOLS/aapt" \
  --apksigner "$ANDROID_BUILD_TOOLS/apksigner"
```

The verifier fails closed unless all of these hold:

- the filename is the channel's only accepted universal APK name;
- the tag matches `versionName`, `versionCode` exceeds the prior channel release, and `minSdk` is 35;
- the application ID is exactly `org.quicklauncher` or `org.quicklauncher.preview` for the selected channel;
- the APK is non-debuggable and signed by the expected certificate using the required signature scheme;
- the recomputed APK SHA-256 matches the redownloaded release metadata; the separate `sha256sum --check` binds it to the redownloaded checksum asset;
- verified provenance names the same repository, tag, source commit, workflow, artifact name, and subject digest.

For a direct download rather than a local file, use `--url` instead of `--apk`; HTTPS is required. A successful command verifies the supplied evidence but does not make an untrusted expected-certificate value trustworthy.

## Maintainer recovery drill

`python3 tools/release/key_recovery_drill.py` uses disposable test keys only. It creates two separately encrypted temporary backups, removes the working copy, restores each independently, proves identical public certificates, and exercises Android v3 signing lineage before deleting its temporary plaintext. Passing this drill is evidence for the procedure, never evidence that production keys were accessed or recovered.
