#!/usr/bin/env python3
"""Emits metadata bound to an inspected stableBenchmark APK for an authorized run."""

import argparse
import json
import re
import sys
import hashlib
from pathlib import Path

from verify_phase9_results import VerificationError, inspect_apk, inspect_installed_apk, load, require


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--results", required=True)
    parser.add_argument("--device-serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--worktree-fingerprint", required=True)
    parser.add_argument("--refresh-rate-hz", required=True, type=float)
    parser.add_argument("--thermal-status", required=True)
    args = parser.parse_args()
    try:
        require(re.fullmatch(r"[0-9a-f]{64}", args.worktree_fingerprint) is not None,
                "invalid worktree fingerprint")
        require(args.refresh_rate_hz > 0, "invalid refresh rate")
        results_path = Path(args.results)
        raw = load(results_path)
        context = raw.get("context", {}).get("build", {})
        sdk = context.get("version", {}).get("sdk")
        require(sdk == 35, "raw results are not from API 35")
        require(bool(context.get("fingerprint")), "raw results lack device fingerprint")
        require(bool(context.get("model")), "raw results lack device model")
        artifact = inspect_apk(args.apk)
        require(artifact["applicationId"] == "org.quicklauncher", "unexpected application ID")
        require(artifact["debugCertificate"], "benchmark APK is not debug-key signed")
        require(not artifact["debuggable"], "benchmark target APK is debuggable")
        require(artifact["profileableShell"], "benchmark target APK is not shell-profileable")
        require(artifact["fixtureReceiver"], "APK is not the benchmark fixture variant")
        installed = inspect_installed_apk(args.adb, args.device_serial, artifact["applicationId"])
        require(installed["apkSha256"] == artifact["apkSha256"],
                "installed benchmark target does not match supplied APK")
        require(installed["deviceBuild"]["sdk"] == sdk,
                "installed target SDK does not match raw results")
        for field in ("fingerprint", "model"):
            require(installed["deviceBuild"][field] == context[field],
                    f"installed target {field} does not match raw results")
    except (VerificationError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(json.dumps({
        "schemaVersion": 2,
        "applicationId": artifact["applicationId"],
        "versionCode": artifact["versionCode"],
        "versionName": artifact["versionName"],
        "variant": "stableBenchmark",
        "fixtureId": "phase9-five-destination-v1",
        "worktreeFingerprint": args.worktree_fingerprint,
        "apkSha256": artifact["apkSha256"],
        "certificateSha256": artifact["certificateSha256"],
        "resultsSha256": hashlib.sha256(results_path.read_bytes()).hexdigest(),
        "installedTarget": installed,
        "benchmarkContext": {
            "sdk": installed["deviceBuild"]["sdk"],
            "fingerprint": installed["deviceBuild"]["fingerprint"],
            "model": installed["deviceBuild"]["model"],
        },
        "deviceState": {
            "thermalStatus": args.thermal_status,
            "refreshRateHz": args.refresh_rate_hz,
        },
    }, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
