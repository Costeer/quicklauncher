# Phase 5 Android platform behavior

Checked on 2026-09-14 against first-party Android and GrapheneOS documentation. This note records only findings that change the implementation or device procedure.

## Widgets

`AppWidgetHost.allocateAppWidgetId()` creates the host-owned ID. The host must call `deleteAppWidgetId()` when a bind or durable commit does not keep it. `startListening()` and `stopListening()` own the update-listener lifetime, while `createView()` creates an `AppWidgetHostView` for one ID and provider. Quicklauncher therefore keeps allocation and view ownership in `:host:platform`, returns opaque render tokens, and lets the runtime coordinator pair every allocation with either one committed `WidgetPlacementRecord` or deletion. [AppWidgetHost reference](https://developer.android.com/reference/android/appwidget/AppWidgetHost)

`bindAppWidgetIdIfAllowed()` can return false, which means the launcher must use the system bind-permission flow. Size changes belong in `updateAppWidgetOptions()`. A false result is not a failed ID allocation, so cancellation of the permission or configuration activity still needs explicit ID deletion. [AppWidgetManager reference](https://developer.android.com/reference/android/appwidget/AppWidgetManager)

## Shortcuts and profiles

`LauncherApps.getShortcuts()` requires shortcut-host permission and can throw when the target user is locked or not running. `pinShortcuts(package, ids, user)` takes the complete ID list for one package and user, and `startShortcut(package, id, ..., user)` is the profile-aware launch route. Quicklauncher must resolve `ProfileSerial` to a current handle for every query, pin, and launch; it cannot cache `UserHandle`. Missing, locked, and inaccessible results become typed unavailable state. [LauncherApps reference](https://developer.android.com/reference/android/content/pm/LauncherApps)

Work and private profile pause or resume uses `UserManager.requestQuietModeEnabled()`. The boolean return reports whether the request was started, not whether the asynchronous profile transition finished. Runtime state must remain transitional until a validated availability callback and refresh. An empty activity list alone cannot prove a locked profile. [UserManager reference](https://developer.android.com/reference/android/os/UserManager)

## Private Space

Android 15 exposes Private Space apps to a launcher only when it holds `ROLE_HOME` and declares the normal `ACCESS_HIDDEN_PROFILES` permission. A launcher declaring the permission must provide a separate container, allow hide and show, support lock and unlock, and remove locked private apps from discovery including search. Android directs launchers to classify the profile through `LauncherApps.getLauncherUserInfo()`, use quiet mode to lock or unlock, and refresh on `ACTION_PROFILE_AVAILABLE` and `ACTION_PROFILE_UNAVAILABLE`. Quicklauncher declares the permission only now that the secure host overlay, durable visibility control, authentication transition, launch, lock, callback refresh, early metadata filtering, and immediate cache purge are integrated and exercised on the reference device. [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-all#private-space) [permission reference](https://developer.android.com/reference/android/Manifest.permission#ACCESS_HIDDEN_PROFILES)

On API 36 and newer, `LauncherUserInfo.getUserConfig()` can mark the locked entry point hidden with `PRIVATE_SPACE_ENTRYPOINT_HIDDEN`; this is distinct from profile availability and must suppress the entry before an unlock action is offered. `LauncherApps.getPrivateSpaceSettingsIntent()` supplies the system-owned visibility/settings route. Both values remain inside `:host:platform` and cross the seam as typed entry-point and action results. An unlock request may return `false` because credential confirmation was launched, so the host keeps an asynchronous transition and waits for validated profile state instead of treating that return as immediate denial. [LauncherUserInfo reference](https://developer.android.com/reference/android/content/pm/LauncherUserInfo#PRIVATE_SPACE_ENTRYPOINT_HIDDEN) [Private Space settings intent](https://developer.android.com/reference/android/content/pm/LauncherApps#getPrivateSpaceSettingsIntent()) [quiet-mode reference](https://developer.android.com/reference/android/os/UserManager#requestQuietModeEnabled(boolean,%20android.os.UserHandle))

GrapheneOS documents user and work profiles as isolated workspaces and notification forwarding between full users as opt-in. Device checks must not confuse GrapheneOS secondary users or forwarded notifications with Android managed-work or Private Space profiles. [GrapheneOS profile features](https://grapheneos.org/features#improved-user-profiles)

## Notification listener

`NotificationListenerService` says clients must wait for `onListenerConnected()` before reading active notifications. After `onListenerDisconnected()`, calls are unsafe except for `requestRebind(ComponentName)`. The adapter therefore has explicit disconnected and unavailable states and emits only a profile/package aggregate after stripping each `StatusBarNotification` and `Notification`. [NotificationListenerService reference](https://developer.android.com/reference/android/service/notification/NotificationListenerService)

Notification-listener access is a special Settings grant, not the Android 13 notification-posting runtime permission. Quicklauncher first opens the component-specific `Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` route after an explicit user action and falls back to the global list only if it cannot resolve. It rechecks `NotificationManager.isNotificationListenerAccessGranted()` before querying and catches framework revocation races so no stale count survives. Restricted-settings recovery opens application details and remains a physical-device procedure because installer provenance and OS policy can change the available control and wording. [Settings route reference](https://developer.android.com/reference/android/provider/Settings#ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS) [NotificationManager reference](https://developer.android.com/reference/android/app/NotificationManager#isNotificationListenerAccessGranted(android.content.ComponentName)) [restricted-settings guidance](https://support.google.com/android/answer/12623953#allowrestrictedsettings)

## Device consequences

- API 35 instrumentation must use a real fixture widget provider and exercise system bind cancellation, successful bind, host-view recreation, and ID cleanup.
- Private Space success cannot be claimed from fakes or a managed profile. The GrapheneOS run must classify the actual private profile, test locked and unlocked states, and inspect every ordinary collection and indicator while locked.
- Work-profile completion needs a real managed profile. A full secondary user does not satisfy that gate.
- Notification restricted-settings behavior must be recorded from the GrapheneOS build under test.
