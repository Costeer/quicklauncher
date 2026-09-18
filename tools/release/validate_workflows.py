#!/usr/bin/env python3
"""Static fail-closed audit for the two protected draft-release workflows."""

from __future__ import annotations

import os
import re
from pathlib import Path

try:
    from .release_lib import ReleaseError
except ImportError:
    from release_lib import ReleaseError

ROOT = Path(__file__).resolve().parents[2]
ACTION_PINS = {
    "actions/checkout": "11bd71901bbe5b1630ceea73d27597364c9af683",
    "actions/setup-java": "c5195efecf7bdfc987ee8bae7a71cb8b11521c00",
    "gradle/actions/setup-gradle": "748248ddd2a24f49513d8f472f81c3a07d4d50e1",
    "android-actions/setup-android": "9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407",
    "actions/upload-artifact": "ea165f8d65b6e75b540449e92b4886f43607fa02",
    "actions/download-artifact": "d3f86a106a0bac45b974a628896c90dbdf5c8093",
    "actions/attest": "1e69f48acb82d1966a394da916b4c1698aa569d6",
}


def _require(text: str, needles: tuple[str, ...], label: str) -> None:
    missing = [needle for needle in needles if needle not in text]
    if missing:
        raise ReleaseError(f"{label} is missing required anchors: {missing}")


def validate_text(text: str, channel: str) -> None:
    other = "preview" if channel == "stable" else "stable"
    upper = channel.upper()
    if "pull_request:" in text or re.search(r"^\s*push:\s*$", text, re.MULTILINE):
        raise ReleaseError(f"{channel} release workflow must be dispatch-only")
    if not re.search(r"^on:\s*\n\s+workflow_dispatch:\s*$", text, re.MULTILINE):
        raise ReleaseError(f"{channel} workflow must declare only workflow_dispatch")
    for action, revision in ACTION_PINS.items():
        for matched_action, matched_revision in re.findall(r"^\s*uses:\s*([^@\s]+)@([^\s#]+)", text, re.MULTILINE):
            if matched_action == action and matched_revision != revision:
                raise ReleaseError(f"{action} is not pinned to the reviewed revision")
    uses = re.findall(r"^\s*uses:\s*([^@\s]+)@([^\s#]+)", text, re.MULTILINE)
    if not uses:
        raise ReleaseError("workflow has no actions")
    for action, revision in uses:
        if action not in ACTION_PINS or not re.fullmatch(r"[0-9a-f]{40}", revision):
            raise ReleaseError(f"unreviewed or mutable action reference: {action}@{revision}")
    secret_marker = f"secrets.QUICKLAUNCHER_{upper}_"
    environment_marker = f"environment: release-{channel}"
    if environment_marker not in text or text.index(environment_marker) > text.index(secret_marker):
        raise ReleaseError(f"{channel} secrets must occur only after the protected environment gate")
    if f"QUICKLAUNCHER_{other.upper()}_" in text:
        raise ReleaseError(f"{channel} workflow references {other} key material")
    required = (
        "contents: read",
        "attestations: write",
        "contents: write",
        "id-token: write",
        "tools/gradle --summary",
        "test \"$GITHUB_REF\" = \"refs/tags/$RELEASE_TAG\"",
        "test \"$(git rev-parse HEAD)\" = \"$SOURCE_COMMIT\"",
        "git fetch --force --no-tags origin \"refs/tags/$RELEASE_TAG:refs/tags/$RELEASE_TAG\"",
        "checkModuleBoundaries",
        "verifyNoGoogleDependencies",
        "buildHealth",
        "checkPhase9Performance",
        ":benchmark:macrobenchmark:checkBenchmarkDependencyLicenses",
        ":app:verifyPaparazziStableDebug",
        ":modules:block:core:verifyPaparazziDebug",
        ":modules:layout:core:verifyPaparazziDebug",
        ":host:editor:verifyPaparazziDebug",
        ":host:runtime:verifyPaparazziDebug",
        ":host:settings:verifyPaparazziDebug",
        "rg -o ':[A-Za-z0-9:_-]+:licensee' .github/workflows/ci.yml",
        "tools/security/release_security_audit.py",
        "tools/release/validate_channels.py",
        "tools/release/validate_workflows.py",
        "tools/performance/audit_benchmark_dependencies.py",
        "--resolved benchmark/macrobenchmark/build/reports/licenses/stable-benchmark-dependencies.txt",
        "--allowlist tools/performance/benchmark-dependency-licenses.txt",
        "go install github.com/rhysd/actionlint/cmd/actionlint@v1.7.7",
        ".github/workflows/ci.yml",
        ".github/workflows/release-stable.yml",
        ".github/workflows/release-preview.yml",
        "actions/attest@",
        "gh release create",
        "--draft",
        "gh release download",
        "gh attestation verify",
        "--deny-self-hosted-runners",
        f"--signer-workflow \"$GITHUB_REPOSITORY/.github/workflows/release-{channel}.yml\"",
        "--source-digest \"$SOURCE_COMMIT\" --source-ref \"refs/tags/$RELEASE_TAG\"",
        "tools/release/validate_release_assets.py",
        "tools/release/verify_release.py",
        "tools/release/previous_version_code.py",
        "--expected-source-commit",
        "--expected-certificate-sha256",
        "--previous-version-code",
        "--checksum \"$download_dir/payload/$asset.sha256\"",
        "--metadata \"$download_dir/payload/release-metadata.json\"",
        "--pattern \"$asset\" --pattern \"$asset.sha256\" --pattern \"release-metadata.json\"",
        "--debuggable-apk-permitted false",
        "--v1-signing-enabled false",
        "--v2-signing-enabled false",
        "--v3-signing-enabled true",
        "--v4-signing-enabled false",
        "mktemp -d",
        "trap 'rm -rf --",
        f"--channel {channel}",
        f"quicklauncher-{channel}-${{RELEASE_TAG}}-universal.apk",
        f"vars.QUICKLAUNCHER_{upper}_CERT_SHA256",
        f"secrets.QUICKLAUNCHER_{upper}_KEYSTORE_B64",
        f"secrets.QUICKLAUNCHER_{upper}_KEYSTORE_PASSWORD",
        f"secrets.QUICKLAUNCHER_{upper}_KEY_ALIAS",
        f"secrets.QUICKLAUNCHER_{upper}_KEY_PASSWORD",
    )
    _require(text, required, f"{channel} workflow")
    forbidden = ("gh release edit", "--latest", "--discussion-category", "release publish", "softprops/action-gh-release", "--allow-test-http")
    present = [marker for marker in forbidden if marker in text]
    if present:
        raise ReleaseError(f"{channel} workflow contains publishing-capable/unsupported operations: {present}")
    if text.count("gh release create") != 1 or text.count("gh release download") != 1:
        raise ReleaseError(f"{channel} workflow must create and redownload exactly once")
    if text.count("subject-path:") != 1:
        raise ReleaseError(f"{channel} workflow must attest exactly one subject")
    if f"group: release-{channel}\n" not in text or f"group: release-{channel}-" in text:
        raise ReleaseError(f"{channel} workflow concurrency must serialize the whole channel")
    if text.count(f"previous_version_code.py --channel {channel}") != 2:
        raise ReleaseError(f"{channel} protected job must resolve and recheck the prior versionCode")
    checkout_refs = re.findall(r"^\s+ref:\s+(.+)$", text, re.MULTILINE)
    if len(checkout_refs) != 3:
        raise ReleaseError(f"{channel} workflow must have exactly three explicit checkouts")
    if "refs/tags" not in checkout_refs[0] or "inputs.tag" not in checkout_refs[0]:
        raise ReleaseError(f"{channel} preflight must check out the requested tag")
    if any(ref.strip() != "${{ needs.preflight.outputs.commit }}" for ref in checkout_refs[1:]):
        raise ReleaseError(f"{channel} downstream jobs must check out the proven commit")


def validate(root: Path = ROOT) -> None:
    for channel in ("stable", "preview"):
        path = root / f".github/workflows/release-{channel}.yml"
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as error:
            raise ReleaseError(f"cannot read {path}: {error}") from error
        validate_text(text, channel)


def main() -> int:
    try:
        validate()
    except ReleaseError as error:
        print(f"release workflow validation failed: {error}", file=os.sys.stderr)
        return 1
    print("Release workflow audit passed: stable and preview are protected, pinned, draft-only, and independently verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
