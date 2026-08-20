# Nested scroll prototype

Date: 2026-08-20

Decision: use boundary handoff as the default and keep edge-only navigation as a user-selectable fallback. Boundary handoff passed the API 35 emulator suite plus the manual and connected checks on a GrapheneOS Pixel 10a. This evidence closes the Phase 0 question.

## Question and scope

This prototype asks whether one continuous vertical gesture can move an alphabetical `LazyColumn`, transfer its unconsumed movement to destination navigation at either list boundary, and return to the list after reversal without a position jump or two consumers acting on the same fling.

The executable is the isolated `:prototypes:nested-scroll` application. It has a separate application ID and no project dependencies. It does not use `:host:*`, contracts, persistence, or the production launcher activity.

Run the proof with:

```sh
./gradlew :prototypes:nested-scroll:installDebug
adb shell am start -n org.quicklauncher.prototypes.nestedscroll/.MainActivity
```

Run the repeatable checks with:

```sh
./gradlew \
  :prototypes:nested-scroll:testDebugUnitTest \
  :prototypes:nested-scroll:connectedDebugAndroidTest \
  :prototypes:nested-scroll:lintDebug \
  :prototypes:nested-scroll:projectHealth \
  :prototypes:nested-scroll:licensee
```

## Handoff rule under test

1. The `LazyColumn` receives pointer movement first.
2. `onPostScroll` sends only the movement the list could not consume to the destination preview.
3. If the pointer reverses while a preview is open, `onPreScroll` consumes only enough movement to unwind that preview. Any movement left in the same event goes to the list. The list position is never reset.
4. Release has one velocity owner. With no destination offset, the list owns the whole fling. With a nonzero destination offset, the navigator owns the whole fling. `onPostFling` records and ignores residual list velocity instead of turning it into navigation.
5. Destination movement completes at 25 percent of the viewport, or at 1,800 px/s when velocity continues the drag direction. An opposing fling cannot complete navigation. Settle animation duration is 160 ms.

The fallback puts a 32 dp vertical activation band immediately inside the left system-gesture inset. It requests no system gesture exclusion. The list scrolls normally outside that band.

## Automated result

The recorded run passed 7 unit tests in 0.011 seconds and 8 Compose UI tests in 59.516 seconds. On 2026-08-20 the complete 8-test connected suite was rerun on the API 35 emulator and passed again. There were no failures, errors, or skips.

| Case | Input | Result |
| --- | --- | --- |
| Slow list drag | 900 ms upward swipe in an 80-item list | List moved, destination count stayed at 0, list owned release velocity |
| Bottom boundary | Scroll to item 80, then 900 ms upward swipe | Moved once to destination `+1` |
| Top boundary | 900 ms downward swipe from the initial position | Moved once to destination `-1` |
| Reversal | `-200 px`, then `+500 px` in one pointer stream | Preview returned to 0, reversal count was 1, destination count stayed at 0, and item 80 left composition as the remaining movement returned to the list |
| Fast fling | 60 px move over a 32 ms test gesture in a 12-item list | List reached its end, list owned velocity, destination count stayed at 0 |
| Large text and short list | 200 percent text, 12 items, bottom-boundary swipe | Rows remained reachable and boundary navigation fired once |
| Too small to scroll | 2 items, upward swipe | List consumed 0 px and navigation fired once |
| Edge-only fallback | 2 items, center swipe followed by edge-band swipe | Center swipe did not navigate; edge-band swipe navigated once |

The state-machine tests also cover a sub-threshold slow boundary drag returning to rest, a continuing high-velocity boundary fling choosing the navigator, and an opposing high-velocity fling returning to rest.

## Emulator and measurements

| Property | Value |
| --- | --- |
| AVD | `quicklauncher_api35`, Pixel 7 hardware profile |
| OS | Android 15, API 35, build `AE3A.240806.043` |
| Security patch | 2024-09-05 |
| Image | Google APIs x86_64 system image revision 9 |
| Emulator | 37.1.11.0 |
| Display | 1080 x 2400, 420 dpi, gesture navigation |
| Prototype viewport | 1,610 px |
| Distance threshold | 403 px, 25 percent of the viewport |
| Left system gesture inset | 78 px |
| Edge activation width | 32 dp, 84 px at this density |
| System gesture exclusion | Empty |

An Android input swipe from `(110, 1900)` to `(110, 900)` over 900 ms started inside the inboard band. The navigator consumed 978 px and moved exactly one destination. The same swipe at `x=50`, inside the 78 px system Back region, produced no app navigation. This is the intended fallback behavior.

The first cold launch after installation took 1,368 ms on this headless emulator. This was an observation, not a benchmark, and sets no performance threshold.

## Physical device status

| Device | Version | Result |
| --- | --- | --- |
| Stock Android Pixel | Unavailable | Not required. No device run was performed. |
| GrapheneOS Pixel 10a | Android 17/API 37, build `2026081301` | Manual state checks passed, then all 8 connected Compose tests passed with Espresso 3.7.0. |

The emulator and GrapheneOS results promote boundary handoff from the prototype. Edge-only mode remains available for users who prefer explicit navigation activation. A later device run must still reject any jump, double navigation, or fling that moves both owners, but that run does not reopen Phase 0.

### GrapheneOS manual result

ADB touch injection exercised the visible prototype state on the Pixel 10a:

| Case | Recorded state |
| --- | --- |
| Slow drag in 80 apps | `destination=0 nav=0 listDrag=979px navDrag=0px velocity=LIST` |
| Top boundary | `destination=-1 nav=1 listDrag=0px navDrag=972px velocity=NAVIGATOR` |
| One-stream reversal | `destination=0 nav=0 offset=0px listDrag=321px navDrag=179px reversals=1 velocity=LIST` |
| Fast fling in 12 apps | `destination=0 nav=0 listDrag=39px navDrag=0px velocity=LIST`; item 12 was visible |
| Two-app handoff | `destination=1 nav=1 listDrag=0px navDrag=985px velocity=NAVIGATOR` |
| Edge-only fallback | Center swipe left `nav=0`; the same swipe from the inboard edge band produced `destination=1 nav=1` |
| 200 percent text, 12 apps | Item 12 remained reachable; the bottom remainder produced `destination=1 nav=1 listDrag=1071px navDrag=202px` |

No case produced a nonzero settled offset or two velocity owners. These measurements validate the state machine on this device, but they do not replace a person's visual check for a one-frame jump.

The first connected run failed before every assertion. Compose UI Test had resolved Espresso Core 3.5.0, whose input strategy reflects `android.hardware.input.InputManager.getInstance()`. API 37 no longer has that method, so all eight cases raised the same `NoSuchMethodException`. Pinning the current Espresso 3.7.0 test runtime switched modern Android to `Context.getSystemService(InputManager::class.java)`. The unchanged eight-test command then passed 8 of 8 on the Pixel in 2 minutes 18 seconds. The API 35 emulator suite also remains 8 of 8.

## Failures and fixes

- The first compile used the wrong `Offset` import and obsolete explicit imports for scope modifiers. The corrected source compiled.
- The first instrumentation compile used the deprecated Compose test rule. Warnings are errors in this project, so the suite now uses the v2 rule.
- Dependency analysis initially rejected direct use of transitive Compose packages. The module now declares every directly used package and passes `projectHealth`.
- A swipe inside the emulator's 78 px system Back region did not reach the edge band. Moving the start point inboard to `x=110` worked without gesture exclusion. This is expected, but it makes the physical device checks important because OEM and GrapheneOS insets may differ.
- The initial Android 17 run exposed Espresso Core 3.5.0's removed `InputManager.getInstance()` dependency. The module now pins Espresso 3.7.0 for its instrumentation runtime, and all eight tests pass on that device.

No layout or block contract depends on this prototype decision. The separate-widget-ID question is covered independently by the [widget-neighbor prototype](widget-neighbors.md).
