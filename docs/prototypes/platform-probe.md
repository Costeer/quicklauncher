# Android platform behavior probe

Date: 2026-08-20

Status: complete. Local checks and the API 35 emulator checks pass. A GrapheneOS Pixel 10a supplies physical Home, Back, Private Space, and route evidence. Stock Pixel and low-memory runs are optional compatibility observations.

## Question and scope

This probe checks five assumptions before production launcher code uses them:

1. A normal application can request and hold `ROLE_HOME` after declaring the Home intent filter.
2. Repeated Home presses reach one `singleTask` activity through `onNewIntent`.
3. Android delivers predictive Back start, progress, cancel, and commit callbacks.
4. `LauncherApps` exposes Private Space only when the launcher holds the Home role and declares `ACCESS_HIDDEN_PROFILES`. A locked private profile must not return launchable apps.
5. A GrapheneOS Settings route is safe to offer only when it resolves to the expected package, is exported, and requires no permission that Quicklauncher lacks.

The code lives in the isolated `:prototypes:platform-probe` Android application module. It has no dependency on production modules. Its application ID is `org.quicklauncher.prototypes.platformprobe`. All counters and events live in process memory.

The probe does not claim a private GrapheneOS API. Its GrapheneOS candidates come from the public Android 15 QPR2 Settings manifest at commit `1949c50`. A route can resolve and still be unusable because the exported activity requires a signature permission. That distinction is the point of the test.

## Build and run

Java 17, Android SDK 36, an API 35 or newer device, and ADB are required.

```sh
./gradlew \
  :prototypes:platform-probe:testDebugUnitTest \
  :prototypes:platform-probe:lintDebug \
  :prototypes:platform-probe:licensee \
  :prototypes:platform-probe:assembleDebug

./gradlew :prototypes:platform-probe:installDebug
adb shell am start -n \
  org.quicklauncher.prototypes.platformprobe/.MainActivity
```

The probe is an isolated Android application module in the root Gradle build. It shares build policy and version declarations, but it has no project dependencies.

## What the screen records

- Android build fingerprint, product, model, display build, and API level.
- Home role availability and ownership.
- `ACCESS_HIDDEN_PROFILES` grant state.
- Entry callback, intent action, categories, flags, and separate Home and app-icon counters.
- Predictive Back start, latest progress, cancellation, and commit counts.
- Every profile visible through `LauncherApps`, including user handle, serial, user type, quiet state, running state, unlock state, and launchable activity count.
- Settings route resolution, resolved component, export state, required permission, self-permission state, and final callability decision.
- Profile broadcasts and user-triggered route or quiet-mode operations.

The event list shows newest entries first. It keeps 160 entries and resets when Android kills the process.

## Automated evidence

`ProbeDecisionsTest` covers behavior that does not need an Android device:

- Home entries are distinct from app-icon entries.
- An unexpected package cannot satisfy a private route check.
- An exported activity with an unheld permission is rejected.
- An exported public route without a permission is callable.
- Home ownership is checked before hidden-profile permission state.
- Locked and unlocked private profiles both stay outside the general app catalog.

On 2026-08-20, the root build passed `assembleDebug`, `lintDebug`, `testDebugUnitTest`, and `licensee` for this module under Java 17 with Android SDK 36. All eight pure decision tests passed. These tests do not prove intent delivery, Back callbacks, role behavior, or profile visibility. Those need a device.

## API 35 emulator procedure

Use an AOSP API 35 image with gesture navigation. Record the AVD name, image revision, build fingerprint, and display size in the result table.

1. Install and open the probe.
2. Tap **Request Home role** and select the probe.
3. Confirm that the screen changes to `held=true`.
4. Press Home three times with a short pause between presses. For an ADB repeatable check, run:

   ```sh
   adb shell input keyevent KEYCODE_HOME
   adb shell input keyevent KEYCODE_HOME
   adb shell input keyevent KEYCODE_HOME
   ```

5. Confirm that three new `onNewIntent entry=HOME` events appear and that `entries Home` increases by three. An activity recreation, an app-icon entry, or a missing event fails this check.
6. Start a Back gesture, move about one third across the screen, then return to the edge. Confirm one start and one cancel, with no commit.
7. Complete a Back gesture. Confirm one start and one commit. The probe should stay open because it consumes the callback.
8. Open both public Settings routes. Confirm that each launches a Settings screen and returns to the probe.
9. Record every GrapheneOS candidate as `UNRESOLVED`, `PERMISSION_DENIED`, or `CALLABLE`. The AOSP emulator is expected to leave GrapheneOS-only actions unresolved.
10. Restore the original Home app after the check:

    ```sh
    adb shell am start -a android.settings.HOME_SETTINGS
    ```

Predictive Back cancellation requires a touch or an automation system that can reverse an in-progress gesture. A single `adb shell input swipe` cannot prove cancellation.

## Stock Pixel procedure

Run the emulator procedure by touch first. Then test Private Space on a Pixel running Android 15 or newer.

1. Keep the probe as the default Home app.
2. In system Settings, create Private Space and install one launchable test app inside it.
3. Unlock Private Space and return to the probe.
4. Confirm that `LauncherApps profiles` includes a row whose type is `android.os.usertype.profile.PRIVATE`.
5. Confirm that the row uses `PRIVATE_CONTAINER_UNLOCKED` and has at least one launchable activity.
6. Tap **Request Private Space lock**.
7. Confirm an `ACTION_PROFILE_UNAVAILABLE` event, `quiet=true`, and zero launchable activities. Any private app count above zero is a failure and appears in red.
8. Tap **Request Private Space unlock**, complete system authentication, and confirm `ACTION_PROFILE_AVAILABLE`, `quiet=false`, and the private activity count returns.
9. Revoke the probe's Home role by selecting the stock launcher. Open the probe from its icon. Confirm `held=false` and `HOME_ROLE_NOT_HELD`. A missing private profile is expected in this state and is not evidence of a platform failure.
10. Restore the probe as Home and confirm the private row returns when unlocked.

Record whether the device hides the private profile row entirely while locked. The production adapter can support either observation, but it must never pass locked app labels, icons, or activities to general modules.

## GrapheneOS Pixel procedure

Use a GrapheneOS Pixel with the same Android major version as the route catalog when possible. Record the full fingerprint. Repeat the Home, repeated Home, predictive Back, and Private Space procedures above.

For Settings routes:

1. Confirm **Public Settings parent** and **Public Security parent** are `CALLABLE` and open successfully.
2. Record the component, permission, and result for each GrapheneOS candidate.
3. Try every enabled GrapheneOS button. Confirm it opens the screen named by the row.
4. A route that reports `PERMISSION_DENIED` must have a disabled button. Use the public Security parent as its fallback.
5. A route that resolves to a package other than `com.android.settings` must report `WRONG_PACKAGE` and stay disabled.
6. If the installed GrapheneOS release changes or removes a route, record it as a catalog-version mismatch. Do not loosen the permission or package checks to make it open.

The Android 15 QPR2 GrapheneOS manifest declares exploit protection and duress password activities as exported, but both require `android.permission.MANAGE_SAFETY_CENTER`. It also protects the app-specific native debugging and hardened malloc routes with `android.permission.WRITE_SECURE_SETTINGS`. A regular launcher should report `PERMISSION_DENIED` for these entries. If the result differs, capture the OS build and resolved activity before changing the catalog.

## Low-memory device procedure

Repeat Home and Back checks on the named low-memory API 35 phone. Then send the probe to the background, create memory pressure with the normal device test setup, and press Home.

The process may restart. In that case, `onCreate entry=HOME` is valid and the in-memory counters reset. The failure condition is a blank screen, a crash, or failure to become responsive. Repeated Home presses after the restart must use `onNewIntent` while the activity remains alive.

## Result record

Fill this table with exact build identifiers. `Not run` is better than a guessed pass.

| Device | OS build | Home role | Repeated Home | Back cancel and commit | Private Space | Route checks | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| API 35 Google APIs emulator | `AE3A.240806.043`, fingerprint `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` | Held after `cmd role add-role-holder`; request intent compiled, chooser UI not verified | Pass: three Home key events produced three `onNewIntent` Home entries in one task | Pass: a reversed low-level touch sequence followed by a completed swipe produced `starts=2 cancels=1 commits=1` | No private profile configured; covered by the physical reference device | Public Settings and Security callable and launched; GrapheneOS-only routes unresolved as expected | Complete for emulator scope |
| Stock Android Pixel | Not run | Not required | Not required | Not required | Not required | Not required | Optional |
| GrapheneOS Pixel 10a | Android 17/API 37, build `2026081301`, fingerprint `google/stallion/stallion:17/CP2A.260805.005/2026081301:user/release-keys` | Shell role assignment held correctly. `createRequestRoleIntent` dispatched after restoring Launcher3, but no chooser appeared and ownership stayed with Launcher3. | Pass: three Home presses produced three `onNewIntent` Home entries in one task | Pass: clean touch injection produced `starts=2 cancels=1 commits=1` | Profile serial 10 was visible and lock/unlock broadcasts arrived. Lock state was correct, but the locked profile still returned 3 launchable activities. | Public parents callable; Security opened Settings. Exploit protection unresolved, duress not exported, native debugging and hardened malloc permission-denied. | Complete evidence; role fallback and host-owned private filtering required |
| Low-memory API 35 phone | Not run | Not required | Not required | Not required | Not required | Not required | Optional |

For each failed cell, copy the relevant event lines and attach a screenshot. Do not summarize a partial check as a device pass.

## Decision rule

The API 35 emulator and GrapheneOS Pixel results accept `singleTask` plus `onNewIntent` for the production Home adapter. They also accept predictive Back for the production overlay stack. The production implementation must retain the role-request fallback and the host-owned Private Space filtering found by the probe. Stock Pixel and low-memory runs may add evidence later but do not gate this decision.

Private Space remains host-owned. General layout and search modules never receive a private profile, even when it is unlocked. The GrapheneOS/API 37 result proves that `LauncherApps.getActivityList` becoming empty is not a safe lock signal: it returned three activities while the profile was quiet, stopped, and locked. The host must check profile policy first and discard every private activity before building general catalogs, search sessions, logs, or previews. A locked item reaching any of those consumers is the release blocker; the raw platform count may be nonzero.

The GrapheneOS catalog stays disabled by default. It may offer a private route only when the probe's complete check returns `CALLABLE` on the current OS build. Public Android Settings parents remain available.

### GrapheneOS Pixel 10a observations

The device had a Private Space profile before testing. With the probe holding `ROLE_HOME` and `ACCESS_HIDDEN_PROFILES`, `LauncherApps.profiles` returned the system user and private serial 10. Unlocking changed the profile to running and emitted `ACTION_PROFILE_AVAILABLE`; locking restored quiet mode, stopped the profile, and emitted the unavailable transition. After the lock settled, the probe reported `quiet=true`, `running=false`, `unlocked=false`, and `launchableActivities=3`. This is a platform-data result, not evidence that Quicklauncher displayed those activities.

The Android 15 QPR2 route catalog is stale on this Android 17 build in exactly the safe direction. The exploit-protection action no longer resolved. Duress Password resolved to a non-exported activity guarded by `MANAGE_SAFETY_CENTER`. App native debugging and hardened malloc resolved to exported activities guarded by `WRITE_SECURE_SETTINGS`. The probe disabled all four buttons. The public Security parent resolved and opened `com.android.settings/.SubSettings`.

The standard Home-role request returned immediately without showing a chooser, even though the probe recorded that it dispatched `RoleManager.createRequestRoleIntent`. Shell assignment and repeated Home delivery worked. Production onboarding therefore needs a GrapheneOS/API 37 fallback to the public Home Settings screen if a role request returns without ownership.

## Primary sources

- [Android 15 Private Space requirements for launchers](https://developer.android.com/about/versions/15/behavior-changes-all#private-space)
- [LauncherApps API reference](https://developer.android.com/reference/android/content/pm/LauncherApps)
- [LauncherUserInfo API reference](https://developer.android.com/reference/android/content/pm/LauncherUserInfo)
- [RoleManager API reference](https://developer.android.com/reference/android/app/role/RoleManager)
- [OnBackAnimationCallback API reference](https://developer.android.com/reference/android/window/OnBackAnimationCallback)
- [GrapheneOS Android 15 QPR2 Settings manifest at the reviewed commit](https://github.com/GrapheneOS/platform_packages_apps_Settings/blob/1949c50cb505537a49bfdbfcb59545151845dcd5/AndroidManifest.xml)
