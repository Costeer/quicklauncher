# Android platform behavior probe

Date: 2026-08-20

Decision: use a `singleTask` Home activity with `onNewIntent`, predictive Back callbacks, a public Home-settings fallback, host-owned Private Space filtering, and fail-closed GrapheneOS route checks.

## Questions

The isolated `:prototypes:platform-probe` app tested Home-role ownership, repeated Home delivery, predictive Back cancel and commit, Private Space visibility, and GrapheneOS Settings route safety. A private route is callable only when it resolves to `com.android.settings`, is exported, and requires no unheld permission.

## Run

```sh
./gradlew \
  :prototypes:platform-probe:testDebugUnitTest \
  :prototypes:platform-probe:lintDebug \
  :prototypes:platform-probe:licensee \
  :prototypes:platform-probe:assembleDebug

./gradlew :prototypes:platform-probe:installDebug
adb shell am start -n org.quicklauncher.prototypes.platformprobe/.MainActivity
```

On a device, acquire or assign Home, press Home three times, exercise a cancelled and completed Back gesture, inspect profiles while Private Space locks and unlocks, and try only routes marked callable. Restore the prior Home app afterward.

## Recorded results

| Device | Result |
| --- | --- |
| API 35 emulator, build `AE3A.240806.043` | Three Home events reached one task through `onNewIntent`; Back recorded one cancel and one commit; public Settings routes opened; GrapheneOS-only routes did not resolve. |
| GrapheneOS Pixel 10a, Android 17/API 37, build `2026081301` | Repeated Home and Back passed. The standard role request returned without ownership, so public Home Settings is required as fallback. Private Space lock state and broadcasts were correct, but the locked profile still returned three activities. All private routes were unresolved, non-exported, or permission-denied; public Security Settings opened. |

The GrapheneOS result proves that an empty activity list is not a safe lock test. Check profile policy first and discard private activities before creating catalogs, search sessions, previews, or logs. General modules never receive private-profile data, even while unlocked.

Stock Pixel and low-memory runs are optional compatibility evidence, not phase gates.

## Primary sources

- [Android 15 Private Space launcher requirements](https://developer.android.com/about/versions/15/behavior-changes-all#private-space)
- [LauncherApps](https://developer.android.com/reference/android/content/pm/LauncherApps)
- [RoleManager](https://developer.android.com/reference/android/app/role/RoleManager)
- [OnBackAnimationCallback](https://developer.android.com/reference/android/window/OnBackAnimationCallback)
- [Reviewed GrapheneOS Settings manifest](https://github.com/GrapheneOS/platform_packages_apps_Settings/blob/1949c50cb505537a49bfdbfcb59545151845dcd5/AndroidManifest.xml)
