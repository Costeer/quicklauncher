import json
import re
import unittest
from pathlib import Path

REPOSITORY = Path(__file__).parents[3]


class BenchmarkVariantIsolationTest(unittest.TestCase):
    def test_fixture_exists_only_in_benchmark_source_set(self):
        expected = REPOSITORY / "app/src/benchmark/java/org/quicklauncher/app/BenchmarkFixtureReceiver.kt"
        self.assertTrue(expected.is_file())
        for source_set in ("main", "stable", "preview", "release"):
            root = REPOSITORY / "app/src" / source_set
            if root.exists():
                self.assertFalse(any(root.rglob("BenchmarkFixtureReceiver.kt")), source_set)
                manifests = list(root.rglob("AndroidManifest.xml"))
                self.assertTrue(all("CONTROL_BENCHMARK_FIXTURE" not in item.read_text() for item in manifests))

    def test_preview_benchmark_variant_is_disabled(self):
        build = (REPOSITORY / "app/build.gradle.kts").read_text()
        self.assertIn('.withFlavor("channel" to "preview")', build)
        self.assertIn('.withBuildType("benchmark")', build)
        self.assertIn("variant.enable = false", build)

    def test_profileinstaller_is_not_a_direct_app_dependency(self):
        build = (REPOSITORY / "app/build.gradle.kts").read_text()
        catalog = (REPOSITORY / "gradle/libs.versions.toml").read_text()
        self.assertNotIn("libs.androidx.profileinstaller", build)
        self.assertNotIn("androidx-profileinstaller", catalog)

    def test_catalog_trace_accepts_benchmark_process_sections(self):
        source = (
            REPOSITORY
            / "benchmark/macrobenchmark/src/main/kotlin/org/quicklauncher/benchmark/macrobenchmark/PhaseNinePerformanceBenchmark.kt"
        ).read_text()
        metric = re.search(
            r"sectionName = ScenarioContract\.CATALOG_TRACE,.*?targetPackageOnly = (true|false)",
            source,
            re.DOTALL,
        )
        self.assertIsNotNone(metric)
        self.assertEqual("false", metric.group(1))

    def test_fixture_enables_public_aosp_settings_provider(self):
        source = (
            REPOSITORY
            / "app/src/benchmark/java/org/quicklauncher/app/BenchmarkFixtureReceiver.kt"
        ).read_text()
        self.assertIn('ContributionId.parse("org.quicklauncher.search/settings")', source)
        self.assertIn("setEnabledSearchProviders(setOf(PUBLIC_SETTINGS_PROVIDER))", source)
        self.assertNotIn("org.quicklauncher.search/graphene-settings", source)

    def test_threshold_metadata_matches_benchmark_sources_and_structural_evidence(self):
        contract = json.loads(
            (REPOSITORY / "tools/performance/phase9-thresholds.json").read_text()
        )
        scenarios = {scenario["name"]: scenario for scenario in contract["scenarios"]}
        self.assertEqual(
            {
                "homeEntry",
                "spatialGesture",
                "appCatalogLoading",
                "searchFirstResult",
                "currentPlusNeighborsMemory",
            },
            set(scenarios),
        )
        self.assertEqual(20, scenarios["spatialGesture"]["scenarioOperations"])
        self.assertEqual(128, scenarios["appCatalogLoading"]["fixtureSize"])
        self.assertEqual("security", scenarios["searchFirstResult"]["query"])
        self.assertEqual(5, scenarios["currentPlusNeighborsMemory"]["destinationCount"])

        benchmark = (
            REPOSITORY
            / "benchmark/macrobenchmark/src/main/kotlin/org/quicklauncher/benchmark/macrobenchmark/PhaseNinePerformanceBenchmark.kt"
        ).read_text()
        fixture = (
            REPOSITORY
            / "app/src/benchmark/java/org/quicklauncher/app/BenchmarkFixtureReceiver.kt"
        ).read_text()
        self.assertIn("const val SPATIAL_TRANSITIONS = 20", benchmark)
        self.assertIn('const val SEARCH_QUERY = "security"', benchmark)
        self.assertIn("private const val CATALOG_SIZE = 128", fixture)
        for scenario in scenarios:
            self.assertRegex(benchmark, rf"fun {re.escape(scenario)}\(\)")
        for constant in ("ACTION_SEED", "ACTION_CATALOG", "FIXTURE_ID"):
            pattern = rf"const val {constant} = \"([^\"]+)\""
            benchmark_value = re.search(pattern, benchmark)
            fixture_value = re.search(pattern, fixture)
            self.assertIsNotNone(benchmark_value)
            self.assertIsNotNone(fixture_value)
            self.assertEqual(benchmark_value.group(1), fixture_value.group(1))

        evidence = scenarios["currentPlusNeighborsMemory"]["structuralEvidence"]
        evidence_path, test_name = evidence.split("#", 1)
        structural_source = (REPOSITORY / evidence_path).read_text()
        self.assertIn(f"fun `{test_name}`()", structural_source)
        self.assertIn(
            "assertEquals(setOf(CENTER, LEFT, RIGHT, UP, DOWN), frame.composedDestinations)",
            structural_source,
        )


if __name__ == "__main__":
    unittest.main()
