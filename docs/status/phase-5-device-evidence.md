# Phase 5 device evidence

Checked on 2026-09-14. The API 35 AOSP requirement and the GrapheneOS reference-device paths pass. A disposable managed profile provisioned by Android's ManagedProvisioning flow supplied the previously missing physical work-profile evidence, and the notification recovery check passed from the intended Obtainium installer path. Phase 5 is complete.

## Device availability

The API 35 AOSP image was provisioned and run locally. The attached GrapheneOS reference phone was:

```text
Manufacturer/model: Google Pixel 10a (stallion)
Fingerprint: google/stallion/stallion:17/CP2A.260805.005/2026091001:user/release-keys
Android/API: 17/37
Build display/ID/incremental: 2026091001 / CP2A.260805.005 / 2026091001
Security patch: 2026-09-01
Release channel: official GrapheneOS Android 17 security preview corresponding to stable 2026091000
Baseline profiles: personal, locked/quiet Private Space, two full secondary users; no pre-existing managed-work profile
Original Home: com.android.launcher3
```

The reference-device identity is supported by the device properties and GrapheneOS's official [2026091000 release notes](https://grapheneos.org/releases#2026091000). Personal user names, app labels, package inventories, and notification data are omitted. A supplemental OEM device was also tested:

```text
Manufacturer/model: Nothing A015
Fingerprint: Nothing/TetrisEEA/Tetris:16/BP2A.250605.031.A3/2606151652:user/release-keys
Android/API: 16/36
Build display/ID/incremental: B4.1-260615-1652 / BP2A.250605.031.A3 / 2606151652
Security patch: 2026-06-01
Profiles: personal, locked/quiet Private Space, OEM dual-app profile; no managed-work profile
```

This device is neither AOSP API 35 nor GrapheneOS, so its results are supplemental and do not satisfy ADR 0029. No fake-platform or JVM result is reported as device behavior.

## Supplemental Android 16 run

The first run on the attached device found an invalid empty-database setup in the duplicate-shortcut Room test; the fixture was corrected to bootstrap the launcher before adding shortcuts. A later complete run found a cancelled old-composition effect racing Activity recreation; the production surface now suppresses only that disposed-owner race, and the real recreation test and final matrix pass. The OEM build cannot grant widget-bind or shortcut-host authority to the test packages, so those two authority-dependent success paths skip rather than being reported as production behavior.

The final complete connected command passed in 2 minutes 34 seconds (384 actionable tasks: 5 executed, 379 up-to-date): 45 tests, 43 passed, 2 skipped, 0 failures. Module totals were `:host:data` 15, `:host:platform` 7, `:host:runtime` 5, `:host:editor` 4, and `:app` 14. Passing coverage includes all Room migration/reopen/rollback cases; real widget allocation, bind cancellation, unique IDs, deletion, and leak checks; notification onboarding/recovery; fail-closed profile policy; production App Recovery actions; folder Back dismissal and restoration across real `MainActivity.recreate()`; item-action opening; and repeated-Home dismissal without replacing the launcher root. Widget bind success/host-view recreation and shortcut pin success were the two skips because the OEM could not grant their test-only host authorities.

Before and after the final run, the current user was 0, the Home-role holder was `ginlemon.flowerfree`, Private Space user 10 remained locked/quiet with state `-1`, no Quicklauncher package remained installed, and no Quicklauncher notification-listener grant remained. One deliberately observed crash run left the Home role temporarily empty; it was immediately restored with `cmd role add-role-holder`, and both the focused regression and final matrix subsequently restored it automatically. No personal app label or notification data is recorded.

The final identity and restoration audit used:

```bash
adb shell getprop ro.build.display.id
adb shell getprop ro.build.id
adb shell getprop ro.build.version.incremental
adb shell getprop ro.build.fingerprint
adb shell cmd role get-role-holders --user 0 android.app.role.HOME
adb shell pm list packages
adb shell settings get secure enabled_notification_listeners
adb shell dumpsys user
```

The one manual recovery command was:

```bash
adb shell cmd role add-role-holder --user 0 android.app.role.HOME ginlemon.flowerfree
```

Final result: the original holder was present, private user 10 retained `QUIET_MODE`, and the package/listener queries contained no Quicklauncher entry.

## Required API 35 AOSP run

The required run passed on a full AOSP phone image:

```text
AVD: quicklauncher_aosp_full35_phase5
Fingerprint: Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys
Android/API: 15/35
Build display/ID: sdk_phone64_x86_64-userdebug 15 AE3A.240806.019 12368160 test-keys / AE3A.240806.019
Security patch: 2024-09-05
Profiles: Owner only
Original Home: com.android.launcher3
```

Both notification-listener Settings and application-details Settings resolved to the AOSP Settings application before testing. The final connected command was:

```bash
./gradlew \
  :host:data:connectedDebugAndroidTest \
  :host:platform:connectedDebugAndroidTest \
  :host:runtime:connectedDebugAndroidTest \
  :host:editor:connectedDebugAndroidTest \
  :app:connectedStableDebugAndroidTest \
  --no-daemon --no-configuration-cache --console=plain
```

The final rerun passed in 3 minutes 9 seconds (384 actionable tasks: 5 executed, 379 up-to-date): 50 tests, 49 passed, 1 skipped, 0 failures. Module totals were `:host:data` 15, `:host:platform` 8, `:host:runtime` 5, `:host:editor` 4, and `:app` 18. The sole skip was shortcut pin success because the image did not grant shortcut-host authority to the temporary Home holder; discovery and pinned-set behavior remain covered through the typed platform tests, and the device path was not reported as passing.

Passing device coverage includes the deterministic fixture provider's real bind denial and success, two unique allocations, failed-bind deletion, a real `AppWidgetHostView`, disposal, and a distinct view after host recreation. Room migrations and reopen/rollback, profile fail-closed behavior, notification onboarding/recovery/revocation/disconnect, actual `MainActivity` recreation, folder and item-action overlays, predictive Back, repeated Home, App Recovery, and Home-role grant/restoration also passed. Resize, copy, neighbor composition, deletion, and restored-placement invariants run through their production coordinators and stores in the connected runtime/data suites without claiming an additional framework interaction that the tests do not perform.

The runs exposed and repaired evidence-harness defects: notification tests now restore the exact prior secure-setting value; Home-role tests establish their own onboarding state, use the observed resumed `MainActivity` lifecycle instead of a stale package report, and retry when the role dialog or Compose replaces an accessibility node. The final rerun also corrected Private Space prerequisite detection to inspect actual verbose `UserInfo` rows instead of matching the platform's user-type definition table. Both focused Home regressions passed before the final complete run.

After the final run, Home was `com.android.launcher3`, no `org.quicklauncher` package remained, notification-listener access had its original empty value, no Quicklauncher widget host/binding/grant remained, and the device still had only Owner. No app label or notification data is recorded. The emulator was stopped after this audit.

## Required GrapheneOS run

The complete connected command shown in the API 35 section was rerun after the managed-profile callback repair. It passed in 3 minutes 8 seconds with 384 actionable tasks (5 executed, 379 up-to-date), 39 tests, 37 passes, 2 explicit skips, and no failures. Module totals were `:host:data` 15, `:host:platform` 7, `:host:runtime` 0, `:host:editor` 0, and `:app` 17. Shortcut pin success skipped because the temporary Home holder lacked shortcut-host authority. The destructive managed-profile test skips in the general matrix unless its explicit orchestration argument is present; its physical result is recorded below. The Compose/Espresso-only runtime, editor, and app-recovery surface suites are SDK-suppressed above API 36 because released Espresso 3.7 reflects on an input API removed in API 37; the same tests ran on the required API 35 image. Platform, Room, UiAutomator production-app, widget, notification, profile, Home, process-recreation, folder, and item-overlay tests ran on GrapheneOS.

The matrix exposed an API 37 notification revocation race: the framework could reject `getActiveNotifications()` after access changed. The adapter now rechecks component access before each query, catches the framework `SecurityException`, clears the stale listener, and emits an empty typed snapshot. Both the focused regression and final matrix passed. It also verified the component-specific notification-listener Settings route with global fallback, widget bind cancellation and success against the deterministic provider, host-view recreation, locked Private Space classification, ordinary-surface suppression, `FLAG_SECURE`, repeated-Home cleanup, and exact role/listener restoration.

The production authentication path was run separately with explicit human authentication:

```bash
./gradlew :app:connectedStableDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.quicklauncher.app.PhaseFiveOverlayInstrumentedTest#authenticatedPrivateSpaceUnlockLaunchAndRelockOnReferenceDevice \
  -Pandroid.testInstrumentationRunnerArguments.phase5PrivateAuth=true \
  --no-daemon --no-configuration-cache --console=plain
```

It passed one test in 1 minute 23 seconds. Quicklauncher acquired Home, classified the real private profile before metadata reads, showed the secure locked overlay, initiated system authentication, observed the asynchronous availability callback, launched one sanitized private item when available, returned Home, locked Private Space, removed its prepared content and indicators, and restored the original state. No private label, icon, shortcut identity, or notification value was logged or recorded.

The managed-work check used a debug-only DPC fixture and Android's visible `ACTION_PROVISION_MANAGED_PROFILE` flow. The created user was reported by Android as `android.os.usertype.profile.MANAGED`, with Quicklauncher as Profile Owner and state `RUNNING_UNLOCKED`; no Private Space, secondary user, shell-created fake, or JVM substitute was used. The fixture publishes only a synthetic activity and dynamic shortcut, and its launcher component is disabled in the personal user. The focused command was:

```bash
adb shell am instrument --user 0 -w \
  -e class org.quicklauncher.app.PhaseFiveOverlayInstrumentedTest#managedWorkProfileDiscoveryBadgingLaunchPauseResumeAndCallbackRestoration \
  -e phase5ManagedWork true \
  org.quicklauncher.test/androidx.test.runner.AndroidJUnitRunner
```

It passed one test in 7.73 seconds. The test verified LauncherApps discovery, the host's mandatory work badge and accessible `Work` suffix, typed app and dynamic-shortcut launch into the managed user, pause to quiet state, immediate app and shortcut removal, personal-profile continuity, resume to available state, callback-driven metadata and placement restoration, and placement cleanup. The device run found that GrapheneOS emits both generic accessible/inaccessible and managed-profile lifecycle broadcasts; all profile-sensitive platform adapters now invalidate on that complete public broadcast set. The disposable managed user was removed with `pm remove-user --wait` after the run.

Notification recovery was repeated through Obtainium 1.6.17, the intended distribution installer named by the architecture. The official APK had SHA-256 `ce1aa65430af809289f4045994838b1b072a2021bd82fceab1a6f4a90d0108d4` and signer certificate SHA-256 `b353601f6a1d5fd6603ae2f50be80cf301367b86b6ab8b1f66243da96cd57362`. Obtainium installed the disposable debug APK through its Direct APK Link support from an ADB-reversed loopback server; Android recorded `installerPackageName=dev.imranr.obtainium`. No release was published and no installer identity was forged.

Visible UI input completed Quicklauncher onboarding and initiated notification access from Launcher Settings. System denial produced the typed denied state and empty indicators. The production recovery action opened Quicklauncher's App Info. A later explicit retry granted access, the production UI reported an active connection, and the original Launcher3 listener remained enabled. Force stop from App Info terminated the listener process and Android rebound it while preserving the grant. Visible system revocation then produced the typed revoked state and removed current indicators immediately. The connected platform test independently covered listener loss and revocation without stale counts. No notification content was inspected or retained.

Final restoration was audited after the focused managed-profile test, Obtainium notification check, and final connected matrix. The disposable managed profile was absent; the original user set remained; Private Space user 10 was stopped with `QUIET_MODE` and state `-1`; Home was `com.android.launcher3`; the enabled-listener value was exactly `com.android.launcher3/com.android.launcher3.notification.NotificationListener`; and Quicklauncher, its test package, Obtainium, TestDPC, the loopback reverse, and the temporary installer APK were absent. No personal user name, app label, shortcut, contact, file, Private Space value, or notification content is recorded. The production device remained usable.
