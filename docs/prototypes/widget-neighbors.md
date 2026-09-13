# Widget neighbor prototype

Date: 2026-08-20

Decision: current and neighbor destinations may host widgets concurrently when every placement owns a distinct Android widget ID. Every failed, cancelled, replaced, or removed allocation needs cleanup.

## Pass condition

The isolated `:prototypes:widget-neighbors` app proves that two live `AppWidgetHostView` instances can use different IDs, keep updating together, survive recreation, and be replaced or removed independently. Its allocation ledger rejects shared IDs and returns abandoned IDs for deletion.

## Run

```sh
./gradlew \
  :prototypes:widget-neighbors:testDebugUnitTest \
  :prototypes:widget-neighbors:assembleDebug \
  :prototypes:widget-neighbors:lintDebug \
  :prototypes:widget-neighbors:projectHealth \
  :prototypes:widget-neighbors:licensee

./gradlew :prototypes:widget-neighbors:installDebug
adb shell am start -n org.quicklauncher.prototypes.widgetneighbors/.MainActivity
adb shell dumpsys appwidget
```

For a clean manual run, cancel the first bind and confirm its ID disappears. Then bind both panels, verify distinct IDs and advancing chronometers, replace one, remove the other, rotate, and use the cleanup control. `dumpsys appwidget` must contain only committed IDs.

## Recorded result

- API 35 Pixel 7 emulator, build `AE3A.240806.043`: IDs 3 and 4 rendered concurrently; cancelled replacement preserved both committed widgets; all 5 ledger tests passed.
- GrapheneOS Pixel 10a, Android 17/API 37, build `2026081301`: IDs 4 and 5 rendered concurrently and cleanup reduced the host to zero widgets. Existing bind permission meant this run did not retest cancellation.

## Still required in Phase 5

- Cancel a real provider configuration activity.
- Recover after process death between allocation and durable recording.
- Test resizing, options, dormant layouts, backup mapping, and restore.

The prototype activity is not production code.
