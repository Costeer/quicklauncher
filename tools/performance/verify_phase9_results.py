#!/usr/bin/env python3
"""Fail-closed verifier for raw AndroidX Phase 9 Macrobenchmark output."""

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


class VerificationError(Exception):
    pass


def load(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"cannot read {path}: {error}") from error


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def samples(benchmark, name, iterations, sampled=False):
    bucket = "sampledMetrics" if sampled else "metrics"
    metric = benchmark.get(bucket, {}).get(name)
    require(isinstance(metric, dict), f"missing metric {name}")
    values = metric.get("runs")
    require(isinstance(values, list) and len(values) == iterations,
            f"metric {name} must have exactly {iterations} run groups")
    if sampled:
        require(all(isinstance(value, list) and value for value in values),
                f"sampled metric {name} contains an empty iteration")
    else:
        require(all(not isinstance(value, list) for value in values),
                f"metric {name} must have one value per iteration")
    flattened = []
    for value in values:
        flattened.extend(value if isinstance(value, list) else [value])
    require(flattened and all(isinstance(v, (int, float)) and math.isfinite(v) for v in flattened),
            f"metric {name} contains invalid samples")
    return flattened


def percentile95(values):
    ordered = sorted(values)
    return ordered[math.ceil(0.95 * len(ordered)) - 1]


def find_sdk_tool(name):
    direct = shutil.which(name)
    if direct:
        return direct
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
             str(Path.home() / ".cache/quicklauncher-android-sdk")]
    candidates = []
    for root in filter(None, roots):
        candidates.extend(Path(root).glob(f"build-tools/*/{name}"))
        candidates.extend(Path(root).glob(f"cmdline-tools/*/bin/{name}"))
    require(candidates, f"Android SDK tool not found: {name}")
    return str(sorted(candidates)[-1])


def tool_output(command):
    try:
        return subprocess.run(command, check=True, text=True, capture_output=True).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        raise VerificationError(f"artifact inspection failed: {' '.join(command)}") from error


def adb_output(command, binary=False):
    try:
        completed = subprocess.run(command, check=True, capture_output=True, text=not binary)
        return completed.stdout
    except (OSError, subprocess.CalledProcessError) as error:
        raise VerificationError(f"installed-target inspection failed: {' '.join(command)}") from error


def inspect_installed_apk(adb, serial, application_id):
    require(re.fullmatch(r"[A-Za-z0-9._:-]+", serial) is not None, "invalid device serial")
    require(re.fullmatch(r"[A-Za-z0-9._]+", application_id) is not None, "invalid application ID")
    prefix = [adb, "-s", serial]
    require(adb_output(prefix + ["get-state"]).strip() == "device", "authorized device is not online")
    paths = [
        line.removeprefix("package:").strip()
        for line in adb_output(prefix + ["shell", "pm", "path", application_id]).splitlines()
        if line.startswith("package:")
    ]
    require(len(paths) == 1, "installed benchmark target must be one universal APK")
    package_path = paths[0]
    require(
        re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk", package_path) is not None,
        "unexpected installed benchmark APK path",
    )
    installed_bytes = adb_output(prefix + ["exec-out", "cat", package_path], binary=True)
    require(bool(installed_bytes), "installed benchmark APK is empty")
    sdk_text = adb_output(prefix + ["shell", "getprop", "ro.build.version.sdk"]).strip()
    require(sdk_text.isdigit(), "installed target device has invalid SDK")
    fingerprint = adb_output(prefix + ["shell", "getprop", "ro.build.fingerprint"]).strip()
    model = adb_output(prefix + ["shell", "getprop", "ro.product.model"]).strip()
    require(bool(fingerprint) and bool(model), "installed target device identity is incomplete")
    return {
        "deviceSerial": serial,
        "packagePath": package_path,
        "apkSha256": hashlib.sha256(installed_bytes).hexdigest(),
        "deviceBuild": {
            "sdk": int(sdk_text),
            "fingerprint": fingerprint,
            "model": model,
        },
    }


def inspect_apk(path):
    apk = Path(path)
    require(apk.is_file(), f"APK does not exist: {apk}")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    apkanalyzer = find_sdk_tool("apkanalyzer")
    apksigner = find_sdk_tool("apksigner")
    application_id = tool_output([apkanalyzer, "manifest", "application-id", str(apk)]).strip()
    version_code = tool_output([apkanalyzer, "manifest", "version-code", str(apk)]).strip()
    version_name = tool_output([apkanalyzer, "manifest", "version-name", str(apk)]).strip()
    manifest = tool_output([apkanalyzer, "manifest", "print", str(apk)])
    certificate = tool_output([apksigner, "verify", "--print-certs", str(apk)])
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", certificate)
    require(match is not None, "APK has no inspectable SHA-256 signing certificate")
    return {
        "applicationId": application_id,
        "versionCode": version_code,
        "versionName": version_name,
        "apkSha256": digest,
        "certificateSha256": match.group(1),
        "debugCertificate": "CN=Android Debug" in certificate,
        "debuggable": 'android:debuggable="true"' in manifest,
        "profileableShell": "<profileable" in manifest and 'android:shell="true"' in manifest,
        "fixtureReceiver": "BenchmarkFixtureReceiver" in manifest,
    }


def verify(raw, metadata, contract, artifact, results_sha256):
    require(contract.get("schemaVersion") == 1, "unsupported threshold schema")
    expected = contract["environment"]
    require(metadata.get("schemaVersion") == 2, "unsupported metadata schema")
    require(metadata.get("applicationId") == "org.quicklauncher", "unexpected application ID")
    require(metadata.get("variant") == expected["variant"], "unexpected app variant")
    require(metadata.get("fixtureId") == expected["fixtureId"], "unexpected fixture")
    for field in ("worktreeFingerprint", "apkSha256", "certificateSha256", "resultsSha256"):
        require(re.fullmatch(r"[0-9a-f]{64}", str(metadata.get(field, ""))) is not None,
                f"invalid {field}")
    require(results_sha256 == metadata["resultsSha256"], "raw benchmark results digest mismatch")
    require(artifact is not None, "built APK evidence is required")
    require(artifact.get("applicationId") == metadata["applicationId"], "APK application ID mismatch")
    require(artifact.get("versionCode") == metadata.get("versionCode"), "APK version code mismatch")
    require(artifact.get("versionName") == metadata.get("versionName"), "APK version name mismatch")
    require(artifact.get("apkSha256") == metadata["apkSha256"], "APK digest mismatch")
    require(artifact.get("certificateSha256") == metadata["certificateSha256"], "APK certificate mismatch")
    require(artifact.get("debugCertificate"), "benchmark APK is not signed by a debug certificate")
    require(not artifact.get("debuggable"), "benchmark target APK is debuggable")
    require(artifact.get("profileableShell"), "benchmark target APK is not shell-profileable")
    require(artifact.get("fixtureReceiver"), "APK is not the benchmark fixture variant")
    installed = metadata.get("installedTarget")
    require(isinstance(installed, dict), "installed benchmark target evidence is required")
    require(re.fullmatch(r"[A-Za-z0-9._:-]+", str(installed.get("deviceSerial", ""))) is not None,
            "invalid installed target device serial")
    require(re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk",
                         str(installed.get("packagePath", ""))) is not None,
            "invalid installed target APK path")
    require(installed.get("apkSha256") == artifact.get("apkSha256"),
            "installed benchmark target digest does not match supplied APK")
    require(metadata.get("deviceState", {}).get("thermalStatus") == "NONE", "device was thermally throttled")
    require(float(metadata.get("deviceState", {}).get("refreshRateHz", 0)) > 0, "missing refresh rate")

    context = raw.get("context", {})
    require(context.get("build", {}).get("version", {}).get("sdk") == expected["sdk"], "wrong device SDK")
    for field in ("fingerprint", "model"):
        require(bool(context.get("build", {}).get(field)), f"missing device {field}")
    captured_context = metadata.get("benchmarkContext", {})
    require(captured_context.get("sdk") == context.get("build", {}).get("version", {}).get("sdk"),
            "captured SDK does not match raw results")
    for field in ("fingerprint", "model"):
        require(captured_context.get(field) == context.get("build", {}).get(field),
                f"captured {field} does not match raw results")
    require(installed.get("deviceBuild") == captured_context,
            "installed target device identity does not match raw results")
    benchmarks = raw.get("benchmarks")
    require(isinstance(benchmarks, list), "missing benchmarks array")
    by_name = {entry.get("name"): entry for entry in benchmarks}
    require(len(by_name) == len(benchmarks), "duplicate or unnamed benchmark")

    results = []
    for scenario in contract["scenarios"]:
        benchmark = by_name.get(scenario["name"])
        require(isinstance(benchmark, dict), f"missing scenario {scenario['name']}")
        require(not benchmark.get("errors") and not benchmark.get("skipped"),
                f"scenario {scenario['name']} failed or skipped")
        require(benchmark.get("repeatIterations") == scenario["iterations"],
                f"scenario {scenario['name']} has wrong iteration count")
        require(benchmark.get("thermalThrottleSleepSeconds") == 0,
                f"scenario {scenario['name']} was throttled or lacks thermal evidence")
        for metric in scenario["metrics"]:
            if "components" in metric:
                component_runs = [
                    samples(benchmark, item, scenario["iterations"])
                    for item in metric["components"]
                ]
                require(len({len(item) for item in component_runs}) == 1, "memory sample counts differ")
                values = [sum(items) / metric["divisor"] for items in zip(*component_runs)]
            else:
                values = samples(
                    benchmark,
                    metric["name"],
                    scenario["iterations"],
                    metric.get("sampled", False),
                )
            value = max(values) if metric["statistic"] == "maximum" else percentile95(values)
            require(value <= metric["maximum"],
                    f"{scenario['name']}.{metric['name']}={value:g} exceeds {metric['maximum']:g}")
            results.append((scenario["name"], metric["name"], value))
    require(set(by_name) == {item["name"] for item in contract["scenarios"]}, "unexpected benchmark scenario")
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results")
    parser.add_argument("metadata")
    parser.add_argument("--apk", required=True, help="built stableBenchmark target APK")
    parser.add_argument("--thresholds", default=str(Path(__file__).with_name("phase9-thresholds.json")))
    args = parser.parse_args()
    try:
        raw_path = Path(args.results)
        results = verify(
            load(raw_path),
            load(args.metadata),
            load(args.thresholds),
            inspect_apk(args.apk),
            hashlib.sha256(raw_path.read_bytes()).hexdigest(),
        )
    except (VerificationError, KeyError, TypeError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    for scenario, metric, value in results:
        print(f"PASS {scenario}.{metric}={value:g}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
