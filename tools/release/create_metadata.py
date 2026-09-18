#!/usr/bin/env python3
"""Create canonical metadata from a signed APK after validating its identity."""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

try:
    from .release_lib import ReleaseError, inspect_apk
    from .verify_release import EXPECTED
except ImportError:
    from release_lib import ReleaseError, inspect_apk
    from verify_release import EXPECTED


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--channel", choices=sorted(EXPECTED), required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--previous-version-code", type=int, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--expected-certificate-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--aapt")
    parser.add_argument("--apksigner")
    args = parser.parse_args()
    try:
        if args.output.exists():
            raise ReleaseError("refusing to overwrite metadata")
        if args.previous_version_code < 0:
            raise ReleaseError("previous version code must be non-negative")
        if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
            raise ReleaseError("source commit must be a full lowercase Git SHA")
        expected_cert = args.expected_certificate_sha256.lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected_cert):
            raise ReleaseError("expected certificate fingerprint must be 64 hex characters")
        identity = inspect_apk(args.apk, aapt=args.aapt, apksigner=args.apksigner)
        policy = EXPECTED[args.channel]
        if identity.application_id != policy["applicationId"]:
            raise ReleaseError("APK application ID does not match channel")
        if identity.certificate_sha256 != expected_cert:
            raise ReleaseError("APK certificate does not match protected expectation")
        if identity.version_code <= args.previous_version_code:
            raise ReleaseError("APK version code is not monotonic")
        if args.tag != f"v{identity.version_name}":
            raise ReleaseError("tag is not aligned with APK versionName")
        if identity.min_sdk != 35 or list(identity.schemes) != ["v3"]:
            raise ReleaseError("release requires minSdk 35 and exactly an APK Signature Scheme v3 signature")
        if not re.fullmatch(policy["asset"], args.apk.name):
            raise ReleaseError("APK filename does not match channel")
        metadata = {
            "schemaVersion": 1,
            "channel": args.channel,
            "tag": args.tag,
            "sourceRepository": "Costeer/quicklauncher",
            "sourceRef": f"refs/tags/{args.tag}",
            "sourceCommit": args.source_commit,
            "workflow": f".github/workflows/release-{args.channel}.yml",
            "assetName": args.apk.name,
            "applicationId": identity.application_id,
            "versionCode": identity.version_code,
            "previousVersionCode": args.previous_version_code,
            "versionName": identity.version_name,
            "minSdk": identity.min_sdk,
            "certificateSha256": identity.certificate_sha256,
            "apkSha256": identity.sha256,
            "signatureSchemes": list(identity.schemes),
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as stream:
            json.dump(metadata, stream, indent=2, sort_keys=True)
            stream.write("\n")
    except ReleaseError as error:
        print(f"metadata creation failed: {error}", file=os.sys.stderr)
        return 1
    print(f"Created release metadata: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
