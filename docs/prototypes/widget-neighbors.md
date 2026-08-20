# Widget neighbor prototype

Date: 2026-08-20

Status: the isolated build, lint, license, JVM tests, and API 35 emulator proof pass. The remaining widget risks listed below belong to production Phase 5 rather than this Phase 0 question.

## Question and pass condition

Can the launcher keep a widget on the current destination and another widget in a simultaneously composed neighbor preview while each placement owns a separate Android widget ID?

The proof passes when all of these conditions hold on an API 35 emulator:

1. The current and neighbor panels render live `AppWidgetHostView` instances at the same time.
2. Each panel reports `bound=true`, a provider, and a different positive widget ID.
3. Both diagnostic chronometers continue counting while the two panels remain visible.
4. Cancelling a binding prompt or provider configuration deletes the new ID and leaves the previously hosted widget untouched.
5. Replacing or removing one placement deletes that placement's old ID without stopping the other placement.

The providers may come from the same package. Identity belongs to each placement, not to the provider component. Sharing one ID between the two panels fails the proof.

## What the prototype contains

The isolated `:prototypes:widget-neighbors` application owns one `AppWidgetHost`. Each destination has a fixed diagnostic provider, so the executable proof does not depend on an OEM widget picker. Pressing its bind button allocates a new ID and calls `bindAppWidgetIdIfAllowed` for that provider. If Android denies direct binding, the app launches `ACTION_APPWIDGET_BIND` with the ID and provider component. It commits the ID only after `AppWidgetManager` reports a bound provider.

Two built-in providers make the test independent of the emulator image:

- `QL current diagnostic`
- `QL neighbor diagnostic`

Each remote view prints its allocated ID and runs a chronometer. The main screen also prints the current ID, neighbor ID, bind state, provider component, and any pending allocation.

The allocation ledger is pure Kotlin. It rejects an ID already owned by the other destination. A cancelled flow returns only the pending ID for deletion. A successful replacement returns the previous ID for deletion after the new binding commits.

The application deletes every ID it knows about when the user presses the reset button or finishes the activity. It also deletes restored IDs whose providers no longer exist. Android can still retain an orphan if the process dies before saved state records a newly allocated ID. The reset button covers normal prototype use; production code will need durable pending-allocation recovery.

## Build and run

Run:

```sh
./gradlew \
  :prototypes:widget-neighbors:testDebugUnitTest \
  :prototypes:widget-neighbors:assembleDebug \
  :prototypes:widget-neighbors:lintDebug \
  :prototypes:widget-neighbors:projectHealth \
  :prototypes:widget-neighbors:licensee

./gradlew :prototypes:widget-neighbors:installDebug
adb shell am start -n org.quicklauncher.prototypes.widgetneighbors/.MainActivity
```

## Emulator procedure

Use an API 35 emulator with no previous copy of the prototype installed. A clean install matters because Android may remember a previous "always allow" binding decision.

1. Start the application. Both status lines should read `no ID`.
2. Press `Bind diagnostic` under `Current destination`. Record the pending ID as `P` if Android opens its binding approval screen.
3. Press Back on the binding approval screen. Confirm the message says `P` was deleted and both destinations still read `no ID`. If Android bound without a prompt, direct binding permission was already present; reinstall the prototype before testing cancellation.
4. Press `Bind diagnostic` under `Current destination` again and approve Android's binding request if it appears.
5. Record the ID printed in the current panel and status line as `C`. Confirm the status says `bound=true`, the provider is `CurrentDiagnosticWidgetProvider`, and the chronometer advances.
6. Press `Bind diagnostic` under `Live neighbor preview` and approve binding if Android asks.
7. Record its ID as `N`. Confirm `C != N`, its provider is `NeighborDiagnosticWidgetProvider`, both status lines say `bound=true`, and both chronometers advance for at least ten seconds while both panels remain visible.
8. Press `Replace diagnostic` under the current destination. Approve binding if Android asks. Record the replacement ID as `R`. Confirm `R != C`, neighbor still owns `N`, and both clocks continue.
9. Run `adb shell dumpsys appwidget`. Find the host `org.quicklauncher.prototypes.widgetneighbors`. Confirm `R` and `N` are present and the cancelled ID `P` and replaced ID `C` are absent.
10. Press `Remove` under the neighbor. Confirm the neighbor returns to `no ID`, the current clock continues, and `N` disappears from `dumpsys appwidget`.
11. Bind the neighbor diagnostic again, rotate the emulator, and confirm both IDs and both live views return.
12. Press `Delete all prototype widget IDs`. Confirm both panels return to `no ID` and the host owns no widget IDs in `dumpsys appwidget`.

The direct diagnostic flow does not exercise a provider configuration activity because neither built-in provider has one. A later product test must bind a provider with a configuration activity, cancel it, and confirm the pending ID disappears while the previous placement remains.

## JVM checks

`AllocationLedgerTest` covers separate current and neighbor IDs, cancellation cleanup, replacement cleanup, duplicate-ID rejection, and clearing committed plus pending allocations. These tests check ownership decisions. They do not prove that Android performed the bind or deleted its system record.

## Recorded result

On 2026-08-20, `assembleDebug`, `lintDebug`, `testDebugUnitTest`, and `licensee` passed under Java 17 with Android SDK 36. All five allocation-ledger tests passed.

The executable proof passed on the `quicklauncher_api35` Pixel 7 emulator running Android 15/API 35, build `AE3A.240806.043` (`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`):

- Android displayed a separate binding approval for each diagnostic provider.
- The current panel hosted `CurrentDiagnosticWidgetProvider` as widget ID 3 while the neighbor panel simultaneously hosted `NeighborDiagnosticWidgetProvider` as widget ID 4.
- Both `AppWidgetHostView` instances remained visible and both chronometers advanced independently.
- `dumpsys appwidget` reported IDs 3 and 4 under host ID 20812, with the expected distinct providers and `widgets.size=2`.
- Cancelling a replacement request for the current panel left IDs 3 and 4 as the host's only widgets. The existing current and neighbor widgets continued running, proving that the uncommitted replacement was deleted without displacing either placement.

This answers the Phase 0 question: simultaneous current-plus-neighbor composition is viable when every live placement owns a separate widget ID. It does not justify sharing one ID between live views.

The result was repeated on a GrapheneOS Pixel 10a running Android 17/API 37, build `2026081301`. The current panel hosted ID 4 and the neighbor hosted ID 5. Both chronometers advanced on screen, and `dumpsys appwidget` reported the expected current and neighbor providers under host ID 20812 with `widgets.size=2`. Android had granted bind access to the prototype host, so this run did not present a cancellable approval dialog. The API 35 emulator result remains the cancellation proof. The device cleanup button reduced the host to `widgets.size=0` before uninstall.

## Not verified in this prototype

- No widget with a provider configuration activity has tested the configuration-cancel path.
- Process death between ID allocation and saved-state persistence has not been tested.
- Widget resizing, size options, dormant layouts, backup remapping, and restoration are outside this Phase 0 question.

Do not copy the activity into production. The result should feed the Phase 5 widget host design: one durable placement owns one widget ID, current and neighbor composition may host distinct IDs concurrently, and every failed or cancelled allocation must enter a cleanup path.

## Platform references

- [Build a widget host](https://developer.android.com/develop/ui/views/appwidgets/host)
- [`AppWidgetHost` API reference](https://developer.android.com/reference/android/appwidget/AppWidgetHost)
- [`AppWidgetManager` actions and result handling](https://developer.android.com/reference/android/appwidget/AppWidgetManager)
