# Nested scroll prototype

Date: 2026-08-20

Decision: default to boundary handoff and retain edge-only navigation as a user option. The isolated `:prototypes:nested-scroll` app passed on API 35 and a GrapheneOS Pixel 10a.

## Rule proved

1. Scrollable content consumes movement first.
2. At its boundary, only unconsumed movement enters destination preview.
3. Reversal first unwinds the preview; remaining movement returns to content without resetting its position.
4. Release has one velocity owner. Content owns it at zero destination offset; navigation owns it after preview begins.
5. Navigation completes at 25 percent of the viewport or 1,800 px/s in the drag direction. Settle duration is 160 ms.

Edge-only mode uses a 32 dp band immediately inside the system Back region and requests no gesture exclusion.

## Run

```sh
./gradlew \
  :prototypes:nested-scroll:testDebugUnitTest \
  :prototypes:nested-scroll:connectedDebugAndroidTest \
  :prototypes:nested-scroll:lintDebug \
  :prototypes:nested-scroll:projectHealth \
  :prototypes:nested-scroll:licensee
```

The suite covers ordinary list scrolling, both boundaries, same-stream reversal, fast fling, large text, content too short to scroll, and edge-only activation.

## Recorded result

- API 35 Pixel 7 emulator, build `AE3A.240806.043`: 7 unit and 8 connected tests passed. A swipe inside the system Back inset did not reach the app; the same swipe in the inboard activation band navigated once.
- GrapheneOS Pixel 10a, Android 17/API 37, build `2026081301`: manual checks and all 8 connected tests passed. No case left a settled offset or assigned velocity to both owners.
- API 37 required Espresso 3.7.0 because Espresso 3.5.0 called a removed `InputManager` method.

No layout or block contract depends on this proof. Future device tests must still reject jumps, double navigation, and dual-owner flings.
