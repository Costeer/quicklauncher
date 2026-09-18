import importlib.util
import json
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).parents[1]
SPEC = importlib.util.spec_from_file_location("verify", ROOT / "verify_phase9_results.py")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class VerifyPhaseNineResultsTest(unittest.TestCase):
    def setUp(self):
        fixtures = Path(__file__).parent / "fixtures"
        self.raw = json.loads((fixtures / "passing-results.json").read_text())
        self.metadata = json.loads((fixtures / "passing-metadata.json").read_text())
        self.contract = json.loads((ROOT / "phase9-thresholds.json").read_text())
        self.artifact = {
            "applicationId": "org.quicklauncher",
            "versionCode": "1",
            "versionName": "0.1.0",
            "apkSha256": "b" * 64,
            "certificateSha256": "c" * 64,
            "debugCertificate": True,
            "debuggable": False,
            "profileableShell": True,
            "fixtureReceiver": True,
        }

    def verify(self):
        return VERIFY.verify(
            self.raw,
            self.metadata,
            self.contract,
            self.artifact,
            self.metadata["resultsSha256"],
        )

    def test_accepts_complete_in_threshold_result(self):
        self.assertEqual(6, len(self.verify()))

    def test_rejects_missing_scenario(self):
        self.raw["benchmarks"].pop()
        with self.assertRaisesRegex(VERIFY.VerificationError, "missing scenario"):
            self.verify()

    def test_rejects_threshold_breach(self):
        self.raw["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["runs"][-1] = 2000
        with self.assertRaisesRegex(VERIFY.VerificationError, "exceeds"):
            self.verify()

    def test_rejects_skipped_iteration(self):
        self.raw["benchmarks"][1]["skipped"] = True
        with self.assertRaisesRegex(VERIFY.VerificationError, "failed or skipped"):
            self.verify()

    def test_rejects_artifact_certificate_mismatch(self):
        self.metadata["certificateSha256"] = "d" * 64
        with self.assertRaisesRegex(VERIFY.VerificationError, "certificate mismatch"):
            self.verify()

    def test_rejects_non_debug_artifact_certificate(self):
        self.artifact["debugCertificate"] = False
        with self.assertRaisesRegex(VERIFY.VerificationError, "debug certificate"):
            self.verify()

    def test_rejects_installed_target_digest_mismatch(self):
        self.metadata["installedTarget"]["apkSha256"] = "d" * 64
        with self.assertRaisesRegex(VERIFY.VerificationError, "installed benchmark target digest"):
            self.verify()

    def test_rejects_missing_installed_target_evidence(self):
        del self.metadata["installedTarget"]
        with self.assertRaisesRegex(VERIFY.VerificationError, "installed benchmark target evidence"):
            self.verify()

    def test_rejects_artifact_version_mismatch(self):
        self.artifact["versionCode"] = "2"
        with self.assertRaisesRegex(VERIFY.VerificationError, "version code mismatch"):
            self.verify()

    def test_rejects_legacy_trace_metric_key(self):
        metric = self.raw["benchmarks"][2]["metrics"].pop("catalogReadyFirstMs")
        self.raw["benchmarks"][2]["metrics"]["catalogReadyMs"] = metric
        with self.assertRaisesRegex(VERIFY.VerificationError, "missing metric catalogReadyFirstMs"):
            self.verify()

    def test_rejects_legacy_memory_metric_key(self):
        metrics = self.raw["benchmarks"][4]["metrics"]
        metrics["memoryRssAnonKb"] = metrics.pop("memoryRssAnonMaxKb")
        with self.assertRaisesRegex(VERIFY.VerificationError, "missing metric memoryRssAnonMaxKb"):
            self.verify()

    def test_rejects_partial_iteration_runs(self):
        self.raw["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["runs"].pop()
        with self.assertRaisesRegex(VERIFY.VerificationError, "exactly 10"):
            self.verify()

    def test_rejects_empty_sampled_iteration(self):
        self.raw["benchmarks"][1]["sampledMetrics"]["frameDurationCpuMs"]["runs"] = [[]]
        with self.assertRaisesRegex(VERIFY.VerificationError, "empty iteration"):
            self.verify()

    def test_rejects_missing_thermal_evidence(self):
        del self.raw["benchmarks"][3]["thermalThrottleSleepSeconds"]
        with self.assertRaisesRegex(VERIFY.VerificationError, "thermal evidence"):
            self.verify()

    def test_rejects_missing_artifact_evidence(self):
        with self.assertRaisesRegex(VERIFY.VerificationError, "APK evidence"):
            VERIFY.verify(
                self.raw,
                self.metadata,
                self.contract,
                None,
                self.metadata["resultsSha256"],
            )

    def test_rejects_raw_result_byte_tampering(self):
        with self.assertRaisesRegex(VERIFY.VerificationError, "results digest mismatch"):
            VERIFY.verify(self.raw, self.metadata, self.contract, self.artifact, "f" * 64)

    def test_rejects_scenario_relabel(self):
        self.raw["benchmarks"][0]["name"] = "homeEntryRelabeled"
        with self.assertRaisesRegex(VERIFY.VerificationError, "missing scenario homeEntry"):
            self.verify()

    def test_rejects_captured_device_relabel(self):
        self.metadata["benchmarkContext"]["model"] = "different-device"
        with self.assertRaisesRegex(VERIFY.VerificationError, "captured model"):
            self.verify()

    def test_rejects_installed_device_identity_mismatch(self):
        self.metadata["installedTarget"]["deviceBuild"]["model"] = "different-device"
        with self.assertRaisesRegex(VERIFY.VerificationError, "installed target device identity"):
            self.verify()

    @patch.object(VERIFY.subprocess, "run")
    def test_installed_apk_inspection_hashes_one_online_universal_apk(self, run):
        payload = b"installed-apk"
        run.side_effect = [
            SimpleNamespace(stdout="device\n"),
            SimpleNamespace(stdout="package:/data/app/~~token/org.quicklauncher-token/base.apk\n"),
            SimpleNamespace(stdout=payload),
            SimpleNamespace(stdout="35\n"),
            SimpleNamespace(stdout="aosp/test/device\n"),
            SimpleNamespace(stdout="emulator\n"),
        ]

        installed = VERIFY.inspect_installed_apk("adb", "emulator-5554", "org.quicklauncher")

        self.assertEqual("emulator-5554", installed["deviceSerial"])
        self.assertEqual(VERIFY.hashlib.sha256(payload).hexdigest(), installed["apkSha256"])
        self.assertEqual({"sdk": 35, "fingerprint": "aosp/test/device", "model": "emulator"},
                         installed["deviceBuild"])
        self.assertEqual(6, run.call_count)

    @patch.object(VERIFY.subprocess, "run")
    def test_installed_apk_inspection_rejects_split_install(self, run):
        run.side_effect = [
            SimpleNamespace(stdout="device\n"),
            SimpleNamespace(stdout="package:/data/app/token/base.apk\npackage:/data/app/token/split.apk\n"),
        ]

        with self.assertRaisesRegex(VERIFY.VerificationError, "one universal APK"):
            VERIFY.inspect_installed_apk("adb", "emulator-5554", "org.quicklauncher")


if __name__ == "__main__":
    unittest.main()
