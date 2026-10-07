# Sundown

Closes apps, or a particular activity of an app, at a time you set or when a timer
runs out. Android, sideloaded, Kotlin + Compose, in the Nocturne design carried
over from On Device AI.

## What it does

- **Schedules:** "every weekday at 23:30, close YouTube, Instagram and Chrome."
- **Timers:** "close Spotify in 45 minutes." Start one from the Today screen by
  tapping a length.
- **Warnings:** optionally, a notification 1–10 minutes ahead with **+10 min**,
  **Skip this time** and **Close now**. Snoozing or skipping moves that one
  occurrence only; tomorrow's time is untouched.
- **Log:** every target of every run with what actually happened, including
  runs that only partly worked and why.

## How an app is closed

Android gives a third-party app no way to stop another app.
`forceStopPackage` is signature-only, and `killBackgroundProcesses` cannot
reach an app that is on screen or holds a foreground service, which is exactly
the video or music player you want gone at night. So Sundown's accessibility
service does what a person would:

1. If the app is on screen, press Home.
2. Open its **App info** page, press **Force stop**, then **OK**.
3. Press Back to return to wherever the phone was.

A dark cover hides the Settings pages while this happens. The button labels are read from the Settings app's own resources,
so this works in any language. An **activity** target is closed with
Back instead, and only if it is on top at the time: an activity can't be removed
from an app that keeps running.

**When it can't:**

- **Phone locked:** background processes are stopped straight away and the full
  close runs the moment the phone is unlocked.
- **Accessibility off, or a button not found** (an unusual Settings layout):
  only background processes are stopped, the Log says so, and a notification
  says what would fix it.

## Things learned the hard way

- **`findAccessibilityNodeInfosByText` returns nothing on Android 16's App info
  page,** while a plain walk of the same tree finds "Force stop". Every lookup
  here walks the tree.
- **An accessibility service's node cache is only refreshed by events it
  subscribes to.** Listen only for window-state changes and every read returns
  the page's first, empty frame. The service therefore also subscribes to
  content changes and ignores them.
- **The Force stop label is not the button.** It is a TextView inside the view
  that takes the click, and the disabled state lives on that outer view.
- **Opening App info can restart the app you just stopped.** Its "Screen time"
  row binds the app's usage-stats service; Chrome is relaunched headless to
  answer it. The UI and tabs are gone. The log calls this out rather than
  treating it as a failure.
- **Alarms** are exact when "Alarms & reminders" is allowed (off by default from
  Android 14) and inexact otherwise. They are re-armed at app start, on boot, on
  package replace, and on time or zone changes, because each of those drops or
  moves them.

## Setup on a phone

1. Install the APK.
2. On the **Schedule** tab, each missing permission shows as a card with the
   button that fixes it; the card disappears once it's granted.
   **Open accessibility settings → Sundown → On.** If the switch
   is greyed out with "Restricted setting": App info → ⋮ → *Allow restricted
   settings*, then try again. An `adb install` is exempt from this.
3. Allow **Alarms & reminders** and **Notifications** from their cards.

## Build

```
./gradlew :app:testDebugUnitTest     # RuleEngine: DST, midnight, snooze/skip, timers
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease       # signed if keystore.properties exists
```

`RuleEngine` is the only place that does date arithmetic, and it is pure, so
everything about *when* is unit-tested on the JVM. Everything about *how*
(Settings, the accessibility tree) can only be checked on a device.
