#!/usr/bin/env python3
"""Independently verify a local or downloaded signed release APK."""

from __future__ import annotations

import argparse
import json
import os
import re
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

try:
    from .release_lib import ReleaseError, inspect_apk, load_json, require_exact_keys, verify_checksum_file
except ImportError:
    from release_lib import ReleaseError, inspect_apk, load_json, require_exact_keys, verify_checksum_file

METADATA_KEYS = (
    "schemaVersion",
    "channel",
    "tag",
    "sourceRepository",
    "sourceRef",
    "sourceCommit",
    "workflow",
    "assetName",
    "applicationId",
    "versionCode",
    "previousVersionCode",
    "versionName",
    "minSdk",
    "certificateSha256",
    "apkSha256",
    "signatureSchemes",
)
EXPECTED = {
    "stable": {"applicationId": "org.quicklauncher", "asset": r"^quicklauncher-stable-v[0-9]+\.[0-9]+\.[0-9]+-universal\.apk$", "version": r"^[0-9]+\.[0-9]+\.[0-9]+$"},
    "preview": {"applicationId": "org.quicklauncher.preview", "asset": r"^quicklauncher-preview-v[0-9]+\.[0-9]+\.[0-9]+-preview(?:\.[0-9]+)?-universal\.apk$", "version": r"^[0-9]+\.[0-9]+\.[0-9]+-preview(?:\.[0-9]+)?$"},
}


def _download(source: str, destination: Path, *, allow_test_http: bool = False) -> None:
    parsed = urllib.parse.urlparse(source)
    allowed_schemes = {"https", "http"} if allow_test_http else {"https"}
    if parsed.scheme not in allowed_schemes or not parsed.netloc:
        raise ReleaseError("download URL must use HTTPS and include a host")
    if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ReleaseError("test-only HTTP downloads are restricted to loopback hosts")
    request = urllib.request.Request(source, headers={"User-Agent": "quicklauncher-release-verifier/1"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response, destination.open("xb") as output:
            if response.status != 200:
                raise ReleaseError(f"download returned HTTP {response.status}")
            declared = response.headers.get("Content-Length")
            if declared and int(declared) > 256 * 1024 * 1024:
                raise ReleaseError("download exceeds 256 MiB limit")
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > 256 * 1024 * 1024:
                    raise ReleaseError("download exceeds 256 MiB limit")
                output.write(chunk)
    except (OSError, ValueError) as error:
        raise ReleaseError(f"download failed: {error}") from error


def verify_provenance(
    path: Path,
    *,
    asset_name: str,
    digest: str,
    repository: str,
    source_ref: str,
    source_commit: str,
    workflow: str,
) -> None:
    document = load_json(path)
    if not isinstance(document, list) or len(document) != 1 or not isinstance(document[0], dict):
        raise ReleaseError("GitHub attestation verification output must contain exactly one result")
    entry = document[0]
    if not isinstance(entry.get("attestation"), dict) or not entry["attestation"]:
        raise ReleaseError("missing verified GitHub attestation bundle")
    result = entry.get("verificationResult")
    if not isinstance(result, dict):
        raise ReleaseError("missing GitHub verificationResult")
    signature = result.get("signature")
    certificate = signature.get("certificate") if isinstance(signature, dict) else None
    if not isinstance(certificate, dict):
        raise ReleaseError("missing verified GitHub signing certificate summary")
    timestamps = result.get("verifiedTimestamps")
    if not isinstance(timestamps, list) or not timestamps or not all(isinstance(item, dict) for item in timestamps):
        raise ReleaseError("verified GitHub attestation has no trusted timestamp evidence")
    repository_url = f"https://github.com/{repository}"
    signer_identity = f"{repository_url}/{workflow}@{source_ref}"
    certificate_values = {
        "issuer": "https://token.actions.githubusercontent.com",
        "subjectAlternativeName": signer_identity,
        "buildSignerURI": signer_identity,
        "buildSignerDigest": source_commit,
        "runnerEnvironment": "github-hosted",
        "sourceRepositoryURI": repository_url,
        "sourceRepositoryDigest": source_commit,
        "sourceRepositoryRef": source_ref,
    }
    for key, expected in certificate_values.items():
        if certificate.get(key) != expected:
            raise ReleaseError(f"verified certificate {key} does not match release policy")
    statement = result.get("statement")
    if not isinstance(statement, dict) or statement.get("_type") != "https://in-toto.io/Statement/v1":
        raise ReleaseError("missing verified in-toto v1 provenance statement")
    if statement.get("predicateType") != "https://slsa.dev/provenance/v1":
        raise ReleaseError("attestation is not SLSA build provenance")
    subjects = statement.get("subject")
    if not isinstance(subjects, list) or len(subjects) != 1:
        raise ReleaseError("provenance must contain exactly one subject")
    subject = subjects[0]
    if not isinstance(subject, dict) or subject.get("name") != asset_name:
        raise ReleaseError("provenance subject name does not match the APK")
    subject_digest = subject.get("digest")
    if not isinstance(subject_digest, dict) or subject_digest != {"sha256": digest}:
        raise ReleaseError("provenance subject digest does not match the APK")
    predicate = statement.get("predicate")
    build_definition = predicate.get("buildDefinition") if isinstance(predicate, dict) else None
    run_details = predicate.get("runDetails") if isinstance(predicate, dict) else None
    if not isinstance(build_definition, dict) or not isinstance(run_details, dict):
        raise ReleaseError("provenance lacks the reviewed GitHub workflow structure")
    if build_definition.get("buildType") != "https://actions.github.io/buildtypes/workflow/v1":
        raise ReleaseError("provenance build type is not the reviewed GitHub workflow type")
    external = build_definition.get("externalParameters")
    workflow_identity = external.get("workflow") if isinstance(external, dict) else None
    if workflow_identity != {"ref": source_ref, "repository": repository_url, "path": workflow}:
        raise ReleaseError("provenance workflow repository/path/ref mismatch")
    dependencies = build_definition.get("resolvedDependencies")
    expected_dependency = {
        "uri": f"git+{repository_url}@{source_ref}",
        "digest": {"gitCommit": source_commit},
    }
    if dependencies != [expected_dependency]:
        raise ReleaseError("provenance source repository/ref/commit mismatch")
    builder = run_details.get("builder")
    if builder != {"id": signer_identity}:
        raise ReleaseError("provenance builder identity mismatch")


def verify_metadata(
    metadata: dict[str, Any],
    *,
    channel: str,
    tag: str,
    expected_certificate: str,
    previous_version_code: int,
) -> None:
    require_exact_keys(metadata, METADATA_KEYS, "release metadata")
    if channel not in EXPECTED:
        raise ReleaseError(f"unsupported channel: {channel}")
    policy = EXPECTED[channel]
    expected_ref = f"refs/tags/{tag}"
    values = {
        "schemaVersion": 1,
        "channel": channel,
        "tag": tag,
        "sourceRepository": "Costeer/quicklauncher",
        "sourceRef": expected_ref,
        "workflow": f".github/workflows/release-{channel}.yml",
        "applicationId": policy["applicationId"],
        "previousVersionCode": previous_version_code,
        "certificateSha256": expected_certificate.lower(),
    }
    for key, expected in values.items():
        if metadata.get(key) != expected:
            raise ReleaseError(f"metadata {key} mismatch: expected {expected!r}")
    if not re.fullmatch(r"[0-9a-f]{40}", str(metadata.get("sourceCommit", ""))):
        raise ReleaseError("sourceCommit must be a full lowercase Git commit SHA")
    if not re.fullmatch(policy["asset"], str(metadata.get("assetName", ""))):
        raise ReleaseError("asset name does not match the channel contract")
    if not isinstance(metadata.get("versionCode"), int) or metadata["versionCode"] <= previous_version_code:
        raise ReleaseError("versionCode must be greater than previousVersionCode")
    if not re.fullmatch(policy["version"], str(metadata.get("versionName", ""))):
        raise ReleaseError("versionName does not match the channel contract")
    if tag != f"v{metadata['versionName']}":
        raise ReleaseError("tag must equal 'v' plus the APK versionName")
    if metadata.get("minSdk") != 35:
        raise ReleaseError("minimum SDK must be exactly 35")
    if not re.fullmatch(r"[0-9a-f]{64}", str(metadata.get("apkSha256", ""))):
        raise ReleaseError("APK SHA-256 must be 64 lowercase hex characters")
    schemes = metadata.get("signatureSchemes")
    if schemes != ["v3"]:
        raise ReleaseError(f"the API-35 release APK must verify with exactly the v3 scheme; reported={schemes!r}")


def verify_release(
    apk: Path,
    checksum_path: Path,
    metadata_path: Path,
    provenance_path: Path,
    *,
    channel: str,
    tag: str,
    expected_certificate: str,
    previous_version_code: int,
    aapt: str | None,
    apksigner: str | None,
    expected_source_commit: str | None,
) -> None:
    metadata = load_json(metadata_path)
    if not isinstance(metadata, dict):
        raise ReleaseError("release metadata must be a JSON object")
    verify_metadata(
        metadata,
        channel=channel,
        tag=tag,
        expected_certificate=expected_certificate,
        previous_version_code=previous_version_code,
    )
    if expected_source_commit and metadata["sourceCommit"] != expected_source_commit:
        raise ReleaseError("release source commit does not match the checked-out tag commit")
    if apk.name != metadata["assetName"]:
        raise ReleaseError("downloaded APK filename does not match metadata")
    identity = inspect_apk(apk, aapt=aapt, apksigner=apksigner)
    comparisons = {
        "applicationId": identity.application_id,
        "versionCode": identity.version_code,
        "versionName": identity.version_name,
        "minSdk": identity.min_sdk,
        "certificateSha256": identity.certificate_sha256,
        "apkSha256": identity.sha256,
        "signatureSchemes": list(identity.schemes),
    }
    for key, actual in comparisons.items():
        if metadata[key] != actual:
            raise ReleaseError(f"APK {key} does not match metadata")
    verify_checksum_file(checksum_path, apk, identity.sha256)
    verify_provenance(
        provenance_path,
        asset_name=apk.name,
        digest=identity.sha256,
        repository=metadata["sourceRepository"],
        source_ref=metadata["sourceRef"],
        source_commit=metadata["sourceCommit"],
        workflow=metadata["workflow"],
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", type=Path)
    source.add_argument("--url")
    parser.add_argument("--allow-test-http", action="store_true", help="test fixtures only; production downloads remain HTTPS-only")
    parser.add_argument("--checksum", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--provenance-statement", type=Path, required=True, help="JSON emitted by a successful 'gh attestation verify --format json'")
    parser.add_argument("--channel", choices=sorted(EXPECTED), required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--expected-certificate-sha256", required=True)
    parser.add_argument("--previous-version-code", type=int, required=True)
    parser.add_argument("--expected-source-commit")
    parser.add_argument("--aapt")
    parser.add_argument("--apksigner")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_certificate_sha256):
        parser.error("--expected-certificate-sha256 must be 64 hex characters")
    if args.previous_version_code < 0:
        parser.error("--previous-version-code must be non-negative")
    try:
        if args.url:
            with tempfile.TemporaryDirectory(prefix="quicklauncher-verify-") as temporary:
                metadata = load_json(args.metadata)
                if not isinstance(metadata, dict) or not isinstance(metadata.get("assetName"), str):
                    raise ReleaseError("metadata must provide assetName before download")
                apk = Path(temporary) / metadata["assetName"]
                _download(args.url, apk, allow_test_http=args.allow_test_http)
                verify_release(
                    apk,
                    args.checksum,
                    args.metadata,
                    args.provenance_statement,
                    channel=args.channel,
                    tag=args.tag,
                    expected_certificate=args.expected_certificate_sha256,
                    previous_version_code=args.previous_version_code,
                    aapt=args.aapt,
                    apksigner=args.apksigner,
                    expected_source_commit=args.expected_source_commit,
                )
        else:
            verify_release(
                args.apk,
                args.checksum,
                args.metadata,
                args.provenance_statement,
                channel=args.channel,
                tag=args.tag,
                expected_certificate=args.expected_certificate_sha256,
                previous_version_code=args.previous_version_code,
                aapt=args.aapt,
                apksigner=args.apksigner,
                expected_source_commit=args.expected_source_commit,
            )
    except ReleaseError as error:
        print(f"release verification failed: {error}", file=os.sys.stderr)
        return 1
    print(f"Release verification passed: {args.channel} {args.tag}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
