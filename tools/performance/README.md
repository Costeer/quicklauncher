# Phase 9 performance tools

These tools define gates; checked-in fixtures are tests and are never release measurements.

For an explicitly authorized API 35 AOSP emulator run, build `:app:assembleStableBenchmark` and `:benchmark:macrobenchmark:assembleStableBenchmark`, capture the worktree fingerprint and device refresh/thermal state, then bind those observations to the exact target APK.

The benchmark target inherits the optimized release build (R8 plus resource shrinking) and differs only where measurement requires a non-debuggable, shell-profileable, debug-key-signed local APK and the signature-protected fixture receiver. The `com.android.test` runner explicitly acknowledges AndroidX's `EMULATOR` configuration error because ADR 0029 requires the pinned emulator; all scenario thresholds, iteration counts, thermal checks, and artifact-binding checks remain fail closed:

```sh
tools/performance/capture_phase9_metadata.py \
  --apk app/build/outputs/apk/stable/benchmark/app-stable-benchmark.apk \
  --results raw-benchmark.json \
  --device-serial AUTHORIZED_SERIAL \
  --worktree-fingerprint FINGERPRINT \
  --refresh-rate-hz HZ \
  --thermal-status NONE > run-metadata.json

tools/performance/verify_phase9_results.py raw-benchmark.json run-metadata.json \
  --apk app/build/outputs/apk/stable/benchmark/app-stable-benchmark.apk
```

Run metadata capture immediately after the benchmark while the explicitly authorized target remains online. Capture resolves the installed `org.quicklauncher` package through serial-pinned `adb`, requires exactly one universal base APK, hashes its installed bytes, and rejects the run unless they exactly match the supplied benchmark APK. It also reads SDK, build fingerprint, and model from that same serial and requires them to match the raw AndroidX context. Verification re-reads the APK and raw results; checks the raw, supplied, and installed SHA-256 digests; binds version code/name; rechecks the installed-device identity; and checks the APK application ID, certificate, non-debuggable/profileable manifest, and benchmark-only fixture receiver. It also requires exact iteration cardinality and zero AndroidX thermal-throttle sleep.

The fixture resets the launcher database and every `LauncherPreferences` field. It intentionally does not mutate OS-wide settings, permissions, installed packages, Home-role ownership, or device thermal/refresh state; the authorized-run procedure records or controls those separately. Catalog measurement uses its bounded synthetic platform, and search enables only the public Settings provider (`org.quicklauncher.search/settings`) that produces the fixed AOSP `Security settings` result.

`checkPhase9Performance` runs the offline verifier tests, the exact resolved benchmark dependency/license audit, and the full `:host:runtime:testDebugUnitTest` module suite. That suite contains the exact `SpatialNavigatorTest.frame contains only the current destination and its cardinal neighbors` production structural evidence for the five-surface composition bound; the macrobenchmark measures only production-app RSS.

Licensee is retained everywhere it supports the applied platform plugin, but Licensee rejects `com.android.test`. The benchmark module therefore adds an exact resolved-graph audit: every coordinate must have one permitted SPDX identifier and repository evidence metadata, with cached POM license metadata checked when present. This is an additional fail-closed gate for the otherwise unsupported module, not a relaxation of repository license policy.
