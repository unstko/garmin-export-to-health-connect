# garmin-export-to-health-connect

An Android app that reads a Garmin account export (the ZIP from Garmin's "Export Your
Data") on the phone and writes the history into Health Connect — steps, heart rate,
daily values, workouts and sleep.

Written for one personal migration in September 2026, after switching from a Garmin
watch to another brand: five years of Garmin data were missing from the new
ecosystem. Published because nothing else does this. Garmin's own Health Connect
link backfills only about 30 days, sync apps reach no further back than Garmin's API
allows, and the tools that do read the full export write into their own databases,
not into Health Connect.

**Status: unmaintained.** Used once, worked for that one export, archived on the
day it was published. No issues, no support, no warranty.

---

## Read this first

**Keep your Garmin account and the export.** Health Connect is not an archive you
control, and whatever displays its data (Google Health, Samsung Health, …) decides
for itself what it shows.

**Import sleep last — or not at all until you have checked everything else.** In a
trial run, deleting records through Health Connect made steps and heart rate
disappear from Google Health, but the sleep session stayed visible there and could
not be removed by hand. Whether Google Health drops it eventually was not known at
the time of writing. The app therefore leaves sleep out unless you tick the box.

**Try it on one day first.** Write a small slice, check that it shows up where you
expect it, delete it with the app's delete button, then run the full import.

**The export contains your health data and your account email in its file names.**
Keep it out of Git — the included `.gitignore` covers `*.zip`, `*.fit` and `*.json`
for exactly this reason — and delete it from the phone when you are done.

**The code is written for the export and the watch it was tested with.** Other
Garmin models write the same FIT messages, but field usage differs between models
and firmware versions. Read the log the app prints after loading the export before
you import anything.

---

## Requirements

- An Android phone with Health Connect (built in from Android 14; an app from the
  Play Store on Android 9–13). The app itself needs Android 9 (API 28).
- The Garmin export ZIP, copied to the phone. Request it under Garmin account →
  Data Management → Export Your Data; Garmin mails a download link.
- To build: JDK 17 and the Android SDK (platform 36). Android Studio works, the
  command line tools do too.

There is no release APK. You build it yourself, which is also the only way to know
what it does with your data. It has no internet permission.

## Usage

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone:

1. **Choose Garmin export (.zip).** The app reads the whole export, including the
   nested ZIPs inside it, and prints what it found: the date range, record counts
   per category, and notes about anything unusual.
2. Optionally enter a **cut-off date**. Everything from that day on is skipped —
   useful when your new watch already covers the time since the switch.
3. **Grant permissions** — write access for eleven data types.
4. **Import into Health Connect.** Records go in batches of 500. Progress is kept
   per category, so an interrupted import resumes where it stopped.
5. Check the result. **Delete everything this app wrote** removes all of it again;
   Health Connect only lets an app delete its own records, so nothing else is
   touched.
6. If Google Health misses part of the resting heart rate, enter the first day it
   does show as cut-off date and tap **Rewrite resting heart rate before the day
   above**. It writes those days again as version 2 of the same records; Health
   Connect replaces a record only when the version is higher.

Keep the screen on and the phone charging during the import; the app holds a
wake lock only while its screen is visible.

Every record carries a stable ID derived from the data. Running the import again
updates records instead of duplicating them.

---

## What it handles

| Garmin export | Health Connect |
|---|---|
| Monitoring FIT files, step counters | Steps, as intervals between Garmin's samples |
| Monitoring FIT files, heart rate | Heart rate, one record per hour |
| Daily summaries (`UDSFile_*.json`) | Distance, floors climbed, active and total calories — one record per day |
| Daily summaries | Resting heart rate, respiratory rate — one value per day, at noon |
| Sleep files (`*_sleepData.json`) | Sleep sessions, oxygen saturation (nightly average) |
| `MetricsMaxMetData_*.json` | VO2 max, the last value of each day |
| Activity FIT files | Workouts (walking, running, cycling, swimming, hiking, elliptical, strength, yoga, breathing; anything else as "other workout"), with their own heart rate |

Beyond the plain mapping:

- **Steps add up to Garmin's daily total.** Garmin's step counters run from local
  midnight, per activity type (walking and running separately); the app turns them
  into intervals and checks each day's sum against the total in the daily summary.
  Any difference is listed in the log, day by day.
- **Workout heart rate replaces the all-day measurement** during a workout, so the
  two do not overlap.
- **Duplicate samples** from repeated syncs are merged.
- **Time zones follow the watch's clock**, not Garmin's daily summaries. On travel
  days the summaries switch to the new zone at its midnight while the watch keeps
  counting in the old one until it syncs; mixing both splits the step counters wrongly.
- **Days without monitoring files** get Garmin's daily step total as one interval
  over the whole day, and the log says so.

## What it does not do

- **Sleep stages.** The export contains per-night totals, no timeline. Sessions are
  written without stages.
- **Stress and Body Battery.** Health Connect has no data type for either.
- **GPS routes.** Workouts are written without a route.
- **Anything newer than the export** — it is a one-time import, not a sync.

---

## What was actually tested

One migration, on 2026-09-28, on a Pixel 9 with Android 17 and Health Connect as part
of the system. The export came from a vívoactive 4 and covers July 2020 to July 2025.

Before the import, the whole export was read on a desktop with the same code
(`OwnExportImport`, see below):

- **The FIT reader** decoded every FIT file in the export; its output was compared
  file by file against the Python library `fitdecode`.
- **Steps matched Garmin's daily total on every day** of the five years. One day had
  no monitoring file and got the whole-day fallback.

The import itself wrote about 101,000 step intervals, 22,500 hours of heart rate,
around 1,750 records each for the daily values, 449 VO2 max values and 62 workouts,
without a single error. Sleep — 18 measured nights — was held back, see above, and
written last in a second run, once everything else had been checked in Google Health.
The second run skipped every category already written.

**Verified afterwards:**

- all records present in Health Connect, month by month, under this app's name
- steps and heart rate for all five years visible in Google Health

**What Google Health made of it:**

- It does not pick up backfilled data all at once. Shortly after the import, only
  the first year of steps was visible; the rest appeared within the same morning.
- Active calories never appeared in Google Health. It shows total calories only;
  once the app wrote those as well, all five years appeared the same day.
- Workouts all appeared, back to 2020. Single workouts years apart are easy to miss
  when scrolling; look up a known date instead.
- Resting heart rate appeared only from late May 2021 on, although Health Connect
  held all of it from July 2020 — the cut-off fell on no batch boundary, and ten days
  later nothing more had come. Writing the missing 311 days again as version 2
  fixed it: within a minute of opening Google Health, all of them were there. Why
  the first pass skipped them is unknown. If something else is missing, the same
  rewrite with a higher version is worth a try.
- VO2 max never appeared. Google Health's VO2 max page names runs with GPS as its
  only source, so it seems to compute the value itself and ignore it from other apps.

**Not verified — treat as unknown:**

- other Garmin watches, other firmware, exports from other years
- other apps reading Health Connect (Samsung Health, …)
- whether Google Health shows imported VO2 max anywhere else
- whether Google Health ever removes a sleep session deleted from Health Connect

---

## Notes

The FIT decoder is written from scratch. Garmin's FIT SDK is available, but its
licence does not allow redistribution under an open-source licence. The decoder
covers only what this app needs: definition and data messages, compressed
timestamp headers, developer fields, big-endian fields and chained files.

Pitfalls found on the way, in case you hit them elsewhere:

- Garmin writes `event` messages a minute ahead. A 16-bit monitoring timestamp
  (`timestamp_16`) must therefore be resolved to the *nearest* matching time, not
  the next one — otherwise samples jump 18 hours into the future.
- The activity type of a monitoring sample often sits only in the lower five bits
  of the combined field 24, not in field 5.
- A step counter's value exactly at midnight belongs to the day that ends there.

Two test classes run against a real export and are skipped otherwise:
`OwnExportImport` (set `GARMIN_ZIP`) prints record counts, `OwnExportCheck` (set
`FIT_DIR` and `FIT_SUMMARY`) writes one summary line per FIT file. Neither prints
health values. Everything else in `app/src/test` uses synthetic FIT files.

```bash
GARMIN_ZIP=/path/to/export.zip ./gradlew testDebugUnitTest --tests '*OwnExportImport*' --rerun -i
```

## License

MIT — see [LICENSE](LICENSE).
